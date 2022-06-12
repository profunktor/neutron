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
import dev.profunktor.pulsar.transactions.Tx

import cats._
import cats.effect._
import cats.syntax.all._
import fs2.concurrent.{ Topic => _ }
import org.apache.pulsar.client.api.{ Producer => JProducer, _ }

trait Producer[F[_], E] {

  /**
    * Sends a message asynchronously.
    */
  def send(msg: E): F[MessageId]

  /**
    * Sends a message associated with a set of properties asynchronously.
    */
  def send(msg: E, properties: Map[String, String]): F[MessageId]

  /**
    * Sends a message asynchronously within a transaction.
    */
  def send(msg: E, tx: Tx): F[MessageId]

  /**
    * Sends a message associated with a set of properties asynchronously within a transaction.
    */
  def send(msg: E, properties: Map[String, String], tx: Tx): F[MessageId]

  /**
    * Same as [[send(msg:E)*]] but it discards its output.
    */
  def send_(msg: E): F[Unit]

  /**
    * Same as `send(msg:E,properties:Map[String, String])` but it discards its output.
    */
  def send_(msg: E, properties: Map[String, String]): F[Unit]

  /**
    * Same as [[send(msg:E)*]] but it discards its output.
    */
  def send_(msg: E, tx: Tx): F[Unit]

  /**
    * Same as `send(msg:E,properties:Map[String, String])` but it discards its output.
    */
  def send_(msg: E, properties: Map[String, String], tx: Tx): F[Unit]

  /**
    * Returns the `ProducerStats` synchronously (blocking call).
    */
  def stats: F[ProducerStats]

  /**
    * Returns the last sequence ID (mainly used for deduplication).
    */
  def lastSequenceId: F[Long]
}

object Producer {

  sealed trait Batching
  object Batching {
    final case class Enabled(maxDelay: FiniteDuration, maxMessages: Int) extends Batching
    case object Disabled extends Batching
  }

  sealed trait Deduplication[F[_], +A]
  object Deduplication {
    case class Disabled[F[_]]() extends Deduplication[F, Nothing]
    case class Enabled[F[_], E](seqIdMaker: SeqIdMaker[F, E]) extends Deduplication[F, E]
    case class Native[F[_]]() extends Deduplication[F, Nothing]
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
        case Deduplication.Disabled() => builder
        case Deduplication.Enabled(_) | Deduplication.Native() =>
          builder.sendTimeout(0, TimeUnit.SECONDS)
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
        maybeTx: Option[Tx],
        properties: Map[String, String]
    ): F[MessageId] = {
      val _msg = enc(msg)

      def cont(f: TypedMessageBuilder[A] => TypedMessageBuilder[A]) =
        settings.logger(msg)(topic.url) &>
            FutureLift[F].futureLift {
              val builder = maybeTx match {
                case Some(Tx.Underlying(tx)) => p.newMessage(tx)
                case _                       => p.newMessage()
              }

              f(
                builder
                  .value(_msg)
                  .properties(properties.asJava)
                  .withShardKey(settings.shardKey(msg))
                  .withMessageKey(settings.messageKey(msg))
              ).sendAsync()
            }

      settings.deduplication match {
        case Deduplication.Enabled(seqIdMaker) =>
          seqIdMaker
            .asInstanceOf[SeqIdMaker[F, E]]
            .make(p.getLastSequenceId(), msg)
            .flatMap { nextId =>
              cont(_.sequenceId(nextId))
            }
        case Deduplication.Disabled() | Deduplication.Native() =>
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
      .map {
        case Left(p) =>
          new Producer[F, E] {
            override def send(msg: E): F[MessageId] =
              sendMessage[E](p, msg, identity, None, Map.empty)

            override def send_(msg: E): F[Unit] = send(msg).void

            override def send(msg: E, tx: Tx): F[MessageId] =
              sendMessage[E](p, msg, identity, Some(tx), Map.empty)

            override def send_(msg: E, tx: Tx): F[Unit] = send(msg, tx).void

            override def send(msg: E, properties: Map[String, String], tx: Tx)
                : F[MessageId] =
              sendMessage[E](p, msg, identity, Some(tx), properties)

            override def send(msg: E, properties: Map[String, String]): F[MessageId] =
              sendMessage[E](p, msg, identity, None, properties)

            override def send_(msg: E, properties: Map[String, String]): F[Unit] =
              send(msg, properties).void

            override def send_(msg: E, properties: Map[String, String], tx: Tx): F[Unit] =
              send(msg, properties, tx).void

            override def stats: F[ProducerStats] =
              Sync[F].blocking(p.getStats())

            override def lastSequenceId: F[Long] =
              Sync[F].delay(p.getLastSequenceId())
          }

        case Right(p) =>
          settings.messageEncoder.fold(
            throw new IllegalArgumentException(
              "Missing message encoder (used when pulsar schema is not set)"
            )
          ) { enc =>
            new Producer[F, E] {
              override def send(msg: E): F[MessageId] =
                sendMessage[Array[Byte]](p, msg, enc, None, Map.empty)

              override def send_(msg: E): F[Unit] = send(msg).void

              override def send(msg: E, tx: Tx): F[MessageId] =
                sendMessage[Array[Byte]](p, msg, enc, Some(tx), Map.empty)

              override def send_(msg: E, tx: Tx): F[Unit] = send(msg, tx).void

              override def send(msg: E, properties: Map[String, String], tx: Tx)
                  : F[MessageId] =
                sendMessage[Array[Byte]](p, msg, enc, Some(tx), properties)

              override def send(msg: E, properties: Map[String, String]): F[MessageId] =
                sendMessage[Array[Byte]](p, msg, enc, None, properties)

              override def send_(msg: E, properties: Map[String, String]): F[Unit] =
                send(msg, properties).void

              override def send_(msg: E, properties: Map[String, String], tx: Tx)
                  : F[Unit] =
                send(msg, properties, tx).void

              override def stats: F[ProducerStats] =
                Sync[F].blocking(p.getStats())

              override def lastSequenceId: F[Long] =
                Sync[F].delay(p.getLastSequenceId())
            }
          }
      }
  }

  // Builder-style abstract class instead of case class to allow for bincompat-friendly extension in future versions.
  sealed abstract class Settings[F[_], E] {
    val name: Option[String]
    val batching: Batching
    val messageKey: E => MessageKey
    val shardKey: E => ShardKey
    val logger: E => Topic.URL => F[Unit]
    val messageEncoder: Option[E => Array[Byte]]
    val schema: Option[Schema[E]]
    val deduplication: Deduplication[F, E]
    val unsafeOps: ProducerBuilder[Any] => ProducerBuilder[Any]
    def withBatching(_batching: Batching): Settings[F, E]
    def withMessageKey(_msgKey: E => MessageKey): Settings[F, E]
    def withName(_name: String): Settings[F, E]
    def withShardKey(_shardKey: E => ShardKey): Settings[F, E]
    def withLogger(_logger: E => Topic.URL => F[Unit]): Settings[F, E]

    /**
      * Enables deduplication with a custom message SequenceId maker.
      *
      * Remember to enable deduplication on the broker too.
      *
      * See: https://pulsar.apache.org/docs/en/cookbooks-deduplication/
      *
      * Example:
      *
      * {{{
      *  val maker = SeqIdMaker.instance[IO, String](
      *    (lastSeqId, msg) => IO.pure(lastSeqId + 1)
      *  )
      *
      *  Producer.Settings[IO, String]().withDeduplication(maker)
      * }}}
      */
    def withDeduplication(seqIdMaker: SeqIdMaker[F, E]): Settings[F, E]

    /**
      * Enables deduplication using the default Pulsar SequenceId maker.
      *
      * Remember to enable deduplication on the broker too.
      *
      * See: https://pulsar.apache.org/docs/en/cookbooks-deduplication/
      */
    def withDeduplication: Settings[F, E]

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
        messageKey: E => MessageKey,
        shardKey: E => ShardKey,
        logger: E => Topic.URL => F[Unit],
        messageEncoder: Option[E => Array[Byte]],
        schema: Option[Schema[E]],
        deduplication: Deduplication[F, E],
        unsafeOps: ProducerBuilder[Any] => ProducerBuilder[Any]
    ) extends Settings[F, E] {
      override def withName(_name: String): Settings[F, E] =
        copy(name = Some(_name))
      override def withBatching(_batching: Batching): Settings[F, E] =
        copy(batching = _batching)
      override def withDeduplication(seqIdMaker: SeqIdMaker[F, E]): Settings[F, E] =
        copy(deduplication = Deduplication.Enabled(seqIdMaker))
      override def withDeduplication: Settings[F, E] =
        copy(deduplication = Deduplication.Native())
      override def withMessageKey(_msgKey: E => MessageKey): Settings[F, E] =
        copy(messageKey = _msgKey)
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
        messageKey = _ => MessageKey.Empty,
        shardKey = _ => ShardKey.Default,
        logger = _ => _ => Applicative[F].unit,
        messageEncoder = None,
        schema = None,
        deduplication = Deduplication.Disabled[F](),
        unsafeOps = identity
      )
  }

}
