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
import scala.jdk.CollectionConverters._

import dev.profunktor.pulsar.internal.FutureLift
import dev.profunktor.pulsar.internal.TypedMessageBuilderOps._

import cats._
import cats.effect._
import cats.syntax.all._
import fs2.concurrent.{ Topic => _ }
import org.apache.pulsar.client.api.{
  MessageId,
  Producer => JProducer,
  ProducerBuilder,
  ProducerStats,
  Schema,
  TypedMessageBuilder
}

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
    * Sends a message associated with a set of properties asynchronously.
    */
  def send(msg: E, properties: Map[String, String]): F[MessageId]

  /**
    * Sends a message associated with a `key` and a set of properties asynchronously.
    */
  def send(msg: E, key: MessageKey, properties: Map[String, String]): F[MessageId]

  /**
    * Same as [[send(msg:E)*]] but it discards its output.
    */
  def send_(msg: E): F[Unit]

  /**
    * Same as `send(msg:E,key:MessageKey)` but it discards its output.
    */
  def send_(msg: E, key: MessageKey): F[Unit]

  /**
    * Same as `send(msg:E,properties:Map[String, String])` but it discards its output.
    */
  def send_(msg: E, properties: Map[String, String]): F[Unit]

  /**
    * Retrieves the `ProducerStats` synchronously (blocking call).
    */
  def stats: F[ProducerStats]
}

object Producer {

  sealed trait Batching
  object Batching {
    final case class Enabled(maxDelay: FiniteDuration, maxMessages: Int) extends Batching
    case object Disabled extends Batching
  }

  sealed trait Deduplication[+A]
  object Deduplication {
    case object Disabled extends Deduplication[Nothing]
    case class Enabled[A](seqIdMaker: SeqIdMaker[A]) extends Deduplication[A]
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
    def batchingConf[A](
        builder: ProducerBuilder[A]
    ): ProducerBuilder[A] =
      settings.batching match {
        case Batching.Disabled =>
          builder.enableBatching(false)
        case Batching.Enabled(delay, _) =>
          builder
            .enableBatching(true)
            .batchingMaxPublishDelay(delay.toMillis, TimeUnit.MILLISECONDS)
            .batchingMaxMessages(5)
      }

    //https://pulsar.apache.org/docs/en/cookbooks-deduplication/
    def deduplicationConf[A](
        builder: ProducerBuilder[A]
    ): ProducerBuilder[A] =
      settings.deduplication match {
        case Deduplication.Disabled   => builder
        case Deduplication.Enabled(_) => builder.sendTimeout(0, TimeUnit.SECONDS)
      }

    def unsafeConf[A](
        builder: ProducerBuilder[A]
    ): ProducerBuilder[A] =
      settings.unsafeOps.asInstanceOf[ProducerBuilder[A] => ProducerBuilder[A]](builder)

    def configure[A]: ProducerBuilder[A] => ProducerBuilder[A] =
      b => unsafeConf(deduplicationConf(batchingConf(b)).topic(topic.url.value))

    /*
     * Internal function that manages Sequence IDs whenever deduplication is enabled.
     * Otherwise, it defaults to the underlying default Java implementation.
     *
     * The acquisition of a new `sequenceId` is dictated by the [[SeqIdMaker]] typeclass.
     */
    def sendMessage[A](
        p: JProducer[A],
        msg: E,
        enc: E => A,
        key: MessageKey,
        properties: Map[String, String],
        prevMsgs: Ref[F, Option[E]]
    ): F[MessageId] = {
      val _msg = enc(msg)

      def cont(f: TypedMessageBuilder[A] => TypedMessageBuilder[A]) =
        settings.logger(msg)(topic.url) &>
            FutureLift[F].futureLift {
              f(
                p.newMessage()
                  .value(_msg)
                  .properties(properties.asJava)
                  .withShardKey(settings.shardKey(msg))
                  .withMessageKey(key)
              ).sendAsync()
            }

      settings.deduplication match {
        case Deduplication.Enabled(seqIdMaker) =>
          prevMsgs
            .modify { prev =>
              val nextId = seqIdMaker
                .asInstanceOf[SeqIdMaker[E]]
                .next(p.getLastSequenceId(), prev, msg)
              Some(msg) -> nextId
            }
            .flatMap(
              nextId =>
                Sync[F].delay(println(s"SEQ ID: $nextId")) *> cont(_.sequenceId(nextId))
            )
        case Deduplication.Disabled =>
          cont(identity)
      }
    }

    Resource
      .make {
        Sync[F].delay {
          (settings.name, settings.schema) match {
            case (Some(n), Some(s)) =>
              configure(client.newProducer(s).producerName(n)).create().asLeft
            case (None, Some(s)) =>
              configure(client.newProducer(s)).create().asLeft
            case (Some(n), None) =>
              configure(client.newProducer().producerName(n)).create().asRight
            case (None, None) =>
              configure(client.newProducer()).create().asRight
          }
        }
      }(p => FutureLift[F].futureLift(p.fold(_.closeAsync(), _.closeAsync())).void)
      .evalMap(p => Ref.of[F, Option[E]](None).map(p -> _))
      .map {
        case (Left(p), prevMsgs) =>
          new Producer[F, E] {
            override def send(
                msg: E,
                key: MessageKey,
                properties: Map[String, String]
            ): F[MessageId] =
              sendMessage[E](p, msg, identity, key, properties, prevMsgs)

            override def send(msg: E, key: MessageKey): F[MessageId] =
              send(msg, key, Map.empty)

            override def send_(msg: E, key: MessageKey): F[Unit] = send(msg, key).void

            override def send(msg: E): F[MessageId] = send(msg, MessageKey.Empty)

            override def send_(msg: E): F[Unit] = send(msg, MessageKey.Empty).void

            override def send(msg: E, properties: Map[String, String]): F[MessageId] =
              send(msg, MessageKey.Empty, properties)

            override def send_(msg: E, properties: Map[String, String]): F[Unit] =
              send(msg, properties).void

            override def stats: F[ProducerStats] =
              Sync[F].blocking(p.getStats())
          }

        case (Right(p), prevMsgs) =>
          settings.messageEncoder.fold(
            throw new IllegalArgumentException(
              "Missing message encoder (used when pulsar schema is not set)"
            )
          ) { enc =>
            new Producer[F, E] {
              override def send(msg: E, key: MessageKey, properties: Map[String, String])
                  : F[MessageId] =
                sendMessage[Array[Byte]](p, msg, enc, key, properties, prevMsgs)

              override def send(msg: E, key: MessageKey): F[MessageId] =
                send(msg, key, Map.empty)

              override def send_(msg: E, key: MessageKey): F[Unit] = send(msg, key).void

              override def send(msg: E): F[MessageId] = send(msg, MessageKey.Empty)

              override def send_(msg: E): F[Unit] = send(msg, MessageKey.Empty).void

              override def send(msg: E, properties: Map[String, String]): F[MessageId] =
                send(msg, MessageKey.Empty, properties)

              override def send_(msg: E, properties: Map[String, String]): F[Unit] =
                send(msg, properties).void

              override def stats: F[ProducerStats] =
                Sync[F].blocking(p.getStats())
            }
          }
      }
  }

  // Builder-style abstract class instead of case class to allow for bincompat-friendly extension in future versions.
  sealed abstract class Settings[F[_], E] {
    val name: Option[String]
    val batching: Batching
    val shardKey: E => ShardKey
    val logger: E => Topic.URL => F[Unit]
    val messageEncoder: Option[E => Array[Byte]]
    val schema: Option[Schema[E]]
    val deduplication: Deduplication[E]
    val unsafeOps: ProducerBuilder[Any] => ProducerBuilder[Any]
    def withBatching(_batching: Batching): Settings[F, E]
    def withShardKey(_shardKey: E => ShardKey): Settings[F, E]
    def withLogger(_logger: E => Topic.URL => F[Unit]): Settings[F, E]

    /**
      * Remember to enable deduplication on the broker too.
      *
      * See: https://pulsar.apache.org/docs/en/cookbooks-deduplication/
      *
      * Example:
      *
      * {{{
      *  Producer.Settings[IO, String]()
      *     .withDeduplication(SeqIdMaker.fromEq[String], "producer-name-1")
      * }}}
      */
    def withDeduplication(seqIdMaker: SeqIdMaker[E], name: String): Settings[F, E]

    /**
      * USE THIS ONE WITH CAUTION!
      *
      * In case Neutron does not yet support what you're looking for, there is a big chance
      * the underlying Java client does.
      *
      * In this case, you can access the underlying `ProducerBuilder` to customize it. E.g.
      *
      * {{{
      * Settings[F, E]().withUnsafeConf(_.autoUpdatePartitions(true))
      * }}}
      *
      * To be used with extreme caution!
      */
    def withUnsafeConf(f: ProducerBuilder[Any] => ProducerBuilder[Any]): Settings[F, E]

    // protected to ensure users don't set it and instead use the proper smart constructors
    protected[pulsar] def withMessageEncoder(f: E => Array[Byte]): Settings[F, E]
    protected[pulsar] def withSchema(_schema: Schema[E]): Settings[F, E]
  }

  /**
    * Producer options such as sharding key, batching, and message logger
    */
  object Settings {
    private case class SettingsImpl[F[_], E](
        name: Option[String],
        batching: Batching,
        shardKey: E => ShardKey,
        logger: E => Topic.URL => F[Unit],
        messageEncoder: Option[E => Array[Byte]],
        schema: Option[Schema[E]],
        deduplication: Deduplication[E],
        unsafeOps: ProducerBuilder[Any] => ProducerBuilder[Any]
    ) extends Settings[F, E] {
      override def withBatching(_batching: Batching): Settings[F, E] =
        copy(batching = _batching)
      override def withDeduplication(
          seqIdMaker: SeqIdMaker[E],
          _name: String
      ): Settings[F, E] =
        copy(deduplication = Deduplication.Enabled(seqIdMaker), name = Some(_name))
      override def withShardKey(_shardKey: E => ShardKey): Settings[F, E] =
        copy(shardKey = _shardKey)
      override def withLogger(_logger: E => (Topic.URL => F[Unit])): Settings[F, E] =
        copy(logger = _logger)
      override def withUnsafeConf(
          f: ProducerBuilder[Any] => ProducerBuilder[Any]
      ): Settings[F, E] =
        copy(unsafeOps = f)
      override protected[pulsar] def withMessageEncoder(
          f: E => Array[Byte]
      ): Settings[F, E] =
        copy(messageEncoder = Some(f))
      override protected[pulsar] def withSchema(_schema: Schema[E]): Settings[F, E] =
        copy(schema = Some(_schema))
    }
    def apply[F[_]: Applicative, E](): Settings[F, E] =
      SettingsImpl[F, E](
        name = None,
        batching = Batching.Disabled,
        shardKey = _ => ShardKey.Default,
        logger = _ => _ => Applicative[F].unit,
        messageEncoder = None,
        schema = None,
        deduplication = Deduplication.Disabled,
        unsafeOps = identity
      )
  }

}
