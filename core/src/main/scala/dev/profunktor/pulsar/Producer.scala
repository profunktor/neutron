/*
 * Copyright 2021 ProfunKtor
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package dev.profunktor.pulsar

import java.util.concurrent.TimeUnit

import scala.concurrent.duration.FiniteDuration

import dev.profunktor.pulsar.internal.FutureLift
import dev.profunktor.pulsar.internal.TypedMessageBuilderOps._

import cats._
import cats.effect._
import cats.syntax.all._
import fs2.concurrent.{ Topic => _ }
import org.apache.pulsar.client.api.{ MessageId, ProducerBuilder, Schema }

trait Producer[F[_], E] {

  /**
    * Sends a message asynchronously.
    */
  def send(msg: E): F[MessageId]

  /**
    * Sends a message associated with a `key` asynchronously.
    */
  def send(msg: E, key: MessageKey): F[MessageId]

  /**
    * Same as [[send(msg:E)*]] but it discards its output.
    */
  def send_(msg: E): F[Unit]

  /**
    * Same as `send(msg:E,key:MessageKey)` but it discards its output.
    */
  def send_(msg: E, key: MessageKey): F[Unit]
}

object Producer {

  sealed trait Batching
  object Batching {
    final case class Enabled(maxDelay: FiniteDuration, maxMessages: Int) extends Batching
    case object Disabled extends Batching
  }

  /**
    * It creates a simple [[Producer]] with the supplied message encoder (schema support disabled).
    */
  def make[F[_]: FutureLift: Parallel: Sync, E](
      client: Pulsar.T,
      topic: Topic.Single,
      messageEncoder: E => Array[Byte]
  ): Resource[F, Producer[F, E]] =
    make[F, E](client, topic, Settings[F, E]().withMessageEncoder(messageEncoder))

  /**
    * It creates a simple [[Producer]] with the supplied message encoder (schema support disabled)
    * and settings.
    */
  def make[F[_]: FutureLift: Parallel: Sync, E](
      client: Pulsar.T,
      topic: Topic.Single,
      messageEncoder: E => Array[Byte],
      settings: Settings[F, E]
  ): Resource[F, Producer[F, E]] = {
    val _settings = Option(settings).getOrElse(Settings[F, E]())
    make[F, E](client, topic, _settings.withMessageEncoder(messageEncoder))
  }

  /**
    * It creates a simple [[Producer]] with the supplied Pulsar schema.
    */
  def make[F[_]: FutureLift: Parallel: Sync, E](
      client: Pulsar.T,
      topic: Topic.Single,
      schema: Schema[E]
  ): Resource[F, Producer[F, E]] =
    make[F, E](client, topic, Settings[F, E]().withSchema(schema))

  /**
    * It creates a simple [[Producer]] with the supplied Pulsar schema and settings
    */
  def make[F[_]: FutureLift: Parallel: Sync, E](
      client: Pulsar.T,
      topic: Topic.Single,
      schema: Schema[E],
      settings: Settings[F, E]
  ): Resource[F, Producer[F, E]] = {
    val _settings = Option(settings).getOrElse(Settings[F, E]())
    make[F, E](client, topic, _settings.withSchema(schema))
  }

  /**
    * It creates a simple [[Producer]] with the supplied settings.
    */
  private def make[F[_]: FutureLift: Parallel: Sync, E](
      client: Pulsar.T,
      topic: Topic.Single,
      settings: Settings[F, E]
  ): Resource[F, Producer[F, E]] = {
    def configure[A](
        producerBuilder: ProducerBuilder[A]
    ): ProducerBuilder[A] =
      settings.batching match {
        case Batching.Enabled(delay, _) =>
          producerBuilder
            .enableBatching(true)
            .batchingMaxPublishDelay(
              delay.toMillis,
              TimeUnit.MILLISECONDS
            )
            .batchingMaxMessages(5)
            .topic(topic.url.value)
        case Batching.Disabled =>
          producerBuilder
            .enableBatching(false)
            .topic(topic.url.value)
      }

    Resource
      .make {
        Sync[F].delay {
          settings.schema match {
            case Some(s) => configure(client.newProducer(s)).create().asLeft
            case None    => configure(client.newProducer()).create().asRight
          }
        }
      }(p => FutureLift[F].futureLift(p.fold(_.closeAsync(), _.closeAsync())).void)
      .map {
        case Left(p) =>
          new Producer[F, E] {
            override def send(msg: E, key: MessageKey): F[MessageId] =
              settings.logger(msg)(topic.url) &> FutureLift[F].futureLift {
                    p.newMessage()
                      .value(msg)
                      .withShardKey(settings.shardKey(msg))
                      .withMessageKey(key)
                      .sendAsync()
                  }

            override def send_(msg: E, key: MessageKey): F[Unit] = send(msg, key).void

            override def send(msg: E): F[MessageId] = send(msg, MessageKey.Empty)

            override def send_(msg: E): F[Unit] = send(msg, MessageKey.Empty).void
          }

        case Right(p) =>
          settings.messageEncoder.fold(
            throw new IllegalArgumentException(
              "Missing message encoder (used when pulsar schema is not set)"
            )
          ) { enc =>
            new Producer[F, E] {
              override def send(msg: E, key: MessageKey): F[MessageId] =
                settings.logger(msg)(topic.url) &> FutureLift[F].futureLift {
                      p.newMessage()
                        .value(enc(msg))
                        .withShardKey(settings.shardKey(msg))
                        .withMessageKey(key)
                        .sendAsync()
                    }

              override def send_(msg: E, key: MessageKey): F[Unit] = send(msg, key).void

              override def send(msg: E): F[MessageId] = send(msg, MessageKey.Empty)

              override def send_(msg: E): F[Unit] = send(msg, MessageKey.Empty).void
            }
          }
      }
  }

  // Builder-style abstract class instead of case class to allow for bincompat-friendly extension in future versions.
  sealed abstract class Settings[F[_], E] {
    val batching: Batching
    val shardKey: E => ShardKey
    val logger: E => Topic.URL => F[Unit]
    val messageEncoder: Option[E => Array[Byte]]
    val schema: Option[Schema[E]]
    def withBatching(_batching: Batching): Settings[F, E]
    def withShardKey(_shardKey: E => ShardKey): Settings[F, E]
    def withLogger(_logger: E => Topic.URL => F[Unit]): Settings[F, E]

    // protected to ensure users don't set it and instead use the proper smart constructors
    protected[pulsar] def withMessageEncoder(f: E => Array[Byte]): Settings[F, E]
    protected[pulsar] def withSchema(_schema: Schema[E]): Settings[F, E]
  }

  /**
    * Producer options such as sharding key, batching, and message logger
    */
  object Settings {
    private case class SettingsImpl[F[_], E](
        batching: Batching,
        shardKey: E => ShardKey,
        logger: E => Topic.URL => F[Unit],
        messageEncoder: Option[E => Array[Byte]],
        schema: Option[Schema[E]]
    ) extends Settings[F, E] {
      override def withBatching(_batching: Batching): Settings[F, E] =
        copy(batching = _batching)
      override def withShardKey(_shardKey: E => ShardKey): Settings[F, E] =
        copy(shardKey = _shardKey)
      override def withLogger(_logger: E => (Topic.URL => F[Unit])): Settings[F, E] =
        copy(logger = _logger)
      override protected[pulsar] def withMessageEncoder(
          f: E => Array[Byte]
      ): Settings[F, E] =
        copy(messageEncoder = Some(f))
      override protected[pulsar] def withSchema(_schema: Schema[E]): Settings[F, E] =
        copy(schema = Some(_schema))
    }
    def apply[F[_]: Applicative, E](): Settings[F, E] =
      SettingsImpl[F, E](
        Batching.Disabled,
        _ => ShardKey.Default,
        _ => _ => Applicative[F].unit,
        None,
        None
      )
  }

}
