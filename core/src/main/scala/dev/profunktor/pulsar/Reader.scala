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

import scala.concurrent.duration.FiniteDuration
import scala.util.control.NoStackTrace

import dev.profunktor.pulsar.Reader.{ Message, MessageAvailable }
import dev.profunktor.pulsar.internal.FutureLift

import cats.Functor
import cats.effect._
import cats.syntax.all._
import fs2._
import org.apache.pulsar.client.api.{ Reader => JReader, _ }

/**
  * A MessageReader can be used to read all the messages currently available in a topic.
  */
trait MessageReader[F[_], E] {
  def read: Stream[F, Message[E]]
  def read1: F[Option[Message[E]]]
  def readUntil(timeout: FiniteDuration): F[Option[Message[E]]]
  def messageAvailable: F[MessageAvailable]
}

/**
  * A Reader can be used to read all the messages currently available in a topic. Only cares about payloads.
  */
trait Reader[F[_], E] {
  def read: Stream[F, E]
  def read1: F[Option[E]]
  def readUntil(timeout: FiniteDuration): F[Option[E]]
  def messageAvailable: F[MessageAvailable]
}

object Reader {
  sealed trait MessageAvailable
  object MessageAvailable {
    case object Yes extends MessageAvailable
    case object No extends MessageAvailable
  }

  case class DecodingFailure(bytes: Array[Byte]) extends NoStackTrace
  case class Message[A](id: MessageId, key: MessageKey, payload: A)

  private def mkPulsarReader[F[_]: Sync: FutureLift, E](
      client: Pulsar.T,
      topic: Topic.Single,
      settings: Settings[F, E]
  ): Resource[F, Either[JReader[E], JReader[Array[Byte]]]] =
    Resource
      .make {
        Sync[F].delay {
          def configure[A](builder: ReaderBuilder[A]): ReaderBuilder[A] =
            builder
              .topic(topic.url.value)
              .startMessageId(settings.startMessageId)
              .startMessageIdInclusive()
              .readCompacted(settings.readCompacted)

          settings.schema match {
            case Some(s) => configure(client.newReader(s)).create().asLeft
            case None    => configure(client.newReader()).create().asRight
          }
        }
      }(c => FutureLift[F].futureLift(c.fold(_.closeAsync(), _.closeAsync())).void)

  private def mkMessageReader[F[_]: FutureLift: Sync, E](
      reader: Either[JReader[E], JReader[Array[Byte]]],
      dec: Option[Array[Byte] => F[E]]
  ): MessageReader[F, E] =
    reader match {
      case Left(c) =>
        new MessageReader[F, E] {
          private def readMsg: F[Message[E]] =
            FutureLift[F].futureLift(c.readNextAsync()).map { m =>
              Message(m.getMessageId, MessageKey(m.getKey), m.getValue)
            }

          override def read: Stream[F, Message[E]] =
            Stream.repeatEval(readMsg)

          override def read1: F[Option[Message[E]]] =
            messageAvailable.flatMap {
              case MessageAvailable.Yes => readMsg.map(Some(_))
              case MessageAvailable.No  => none.pure[F]
            }

          override def readUntil(timeout: FiniteDuration): F[Option[Message[E]]] =
            messageAvailable.flatMap {
              case MessageAvailable.Yes =>
                Sync[F].delay(c.readNext(timeout.length.toInt, timeout.unit)).map { m =>
                  Some(Message(m.getMessageId, MessageKey(m.getKey), m.getValue))
                }
              case MessageAvailable.No =>
                none.pure[F]
            }

          override def messageAvailable: F[MessageAvailable] =
            FutureLift[F].futureLift(c.hasMessageAvailableAsync).map { hasAvailable =>
              if (hasAvailable) MessageAvailable.Yes else MessageAvailable.No
            }
        }
      case Right(c) =>
        dec.fold(
          throw new IllegalArgumentException(
            "Missing message decoder (used when pulsar schema is not set)"
          )
        ) { dec =>
          new MessageReader[F, E] {
            private def readMsg: F[Message[E]] =
              FutureLift[F].futureLift(c.readNextAsync()).flatMap { m =>
                dec(m.getValue()).map(Message(m.getMessageId, MessageKey(m.getKey), _))
              }

            override def read: Stream[F, Message[E]] =
              Stream.repeatEval(readMsg)

            override def read1: F[Option[Message[E]]] =
              messageAvailable.flatMap {
                case MessageAvailable.Yes => readMsg.map(Some(_))
                case MessageAvailable.No  => none.pure[F]
              }

            override def readUntil(timeout: FiniteDuration): F[Option[Message[E]]] =
              messageAvailable.flatMap {
                case MessageAvailable.Yes =>
                  Sync[F].delay(c.readNext(timeout.length.toInt, timeout.unit)).flatMap {
                    m =>
                      dec(m.getValue()).map { v =>
                        Some(Message(m.getMessageId, MessageKey(m.getKey), v))
                      }
                  }
                case MessageAvailable.No =>
                  none.pure[F]
              }

            override def messageAvailable: F[MessageAvailable] =
              FutureLift[F].futureLift(c.hasMessageAvailableAsync).map { hasAvailable =>
                if (hasAvailable) MessageAvailable.Yes else MessageAvailable.No
              }
          }
        }

    }

  private def mkPayloadReader[F[_]: Functor, E](m: MessageReader[F, E]): Reader[F, E] =
    new Reader[F, E] {
      override def read: Stream[F, E]  = m.read.map(_.payload)
      override def read1: F[Option[E]] = m.read1.map(_.map(_.payload))
      override def readUntil(timeout: FiniteDuration): F[Option[E]] =
        m.readUntil(timeout).map(_.map(_.payload))
      override def messageAvailable: F[MessageAvailable] = m.messageAvailable
    }

  /**
    * It creates a [[Reader]] with the supplied Pulsar schema.
    */
  def make[F[_]: FutureLift: Sync, E](
      client: Pulsar.T,
      topic: Topic.Single,
      schema: Schema[E]
  ): Resource[F, Reader[F, E]] =
    make[F, E](client, topic, Settings[F, E]().withSchema(schema))

  /**
    * It creates a [[Reader]] with the supplied Pulsar schema and settings.
    */
  def make[F[_]: FutureLift: Sync, E](
      client: Pulsar.T,
      topic: Topic.Single,
      schema: Schema[E],
      settings: Settings[F, E]
  ): Resource[F, Reader[F, E]] = {
    val _settings = settings.withSchema(schema)
    make[F, E](client, topic, _settings)
  }

  /**
    * It creates a [[Reader]] with the supplied message decoder.
    */
  def make[F[_]: FutureLift: Sync, E](
      client: Pulsar.T,
      topic: Topic.Single,
      messageDecoder: Array[Byte] => F[E]
  ): Resource[F, Reader[F, E]] =
    make[F, E](client, topic, Settings[F, E]().withMessageDecoder(messageDecoder))

  /**
    * It creates a [[Reader]] with the supplied message decoder and settings.
    */
  def make[F[_]: FutureLift: Sync, E](
      client: Pulsar.T,
      topic: Topic.Single,
      messageDecoder: Array[Byte] => F[E],
      settings: Settings[F, E]
  ): Resource[F, Reader[F, E]] = {
    val _settings = settings.withMessageDecoder(messageDecoder)
    make[F, E](client, topic, _settings)
  }

  /**
    * It creates a [[Reader]] with the supplied [[Settings]].
    */
  private def make[F[_]: FutureLift: Sync, E](
      client: Pulsar.T,
      topic: Topic.Single,
      settings: Settings[F, E]
  ): Resource[F, Reader[F, E]] =
    mkPulsarReader[F, E](client, topic, settings)
      .map(c => mkPayloadReader(mkMessageReader[F, E](c, settings.messageDecoder)))

  /**
    * It creates a [[MessageReader]] with the supplied Pulsar schema.
    */
  def messageReader[F[_]: FutureLift: Sync, E](
      client: Pulsar.T,
      topic: Topic.Single,
      schema: Schema[E]
  ): Resource[F, MessageReader[F, E]] =
    messageReader[F, E](client, topic, Settings[F, E]().withSchema(schema))

  /**
    * It creates a [[MessageReader]] with the supplied Pulsar schema and settings.
    */
  def messageReader[F[_]: FutureLift: Sync, E](
      client: Pulsar.T,
      topic: Topic.Single,
      schema: Schema[E],
      settings: Settings[F, E]
  ): Resource[F, MessageReader[F, E]] = {
    val _settings = settings.withSchema(schema)
    messageReader[F, E](client, topic, _settings)
  }

  /**
    * It creates a [[MessageReader]] with the supplied message decoder.
    */
  def messageReader[F[_]: FutureLift: Sync, E](
      client: Pulsar.T,
      topic: Topic.Single,
      messageDecoder: Array[Byte] => F[E]
  ): Resource[F, MessageReader[F, E]] =
    messageReader[F, E](
      client,
      topic,
      Settings[F, E]().withMessageDecoder(messageDecoder)
    )

  /**
    * It creates a [[MessageReader]] with the supplied message decoder and settings.
    */
  def messageReader[F[_]: FutureLift: Sync, E](
      client: Pulsar.T,
      topic: Topic.Single,
      messageDecoder: Array[Byte] => F[E],
      settings: Settings[F, E]
  ): Resource[F, MessageReader[F, E]] = {
    val _settings = settings.withMessageDecoder(messageDecoder)
    messageReader[F, E](client, topic, _settings)
  }

  /**
    * It creates a [[MessageReader]] with the supplied [[Settings]].
    */
  private def messageReader[F[_]: FutureLift: Sync, E](
      client: Pulsar.T,
      topic: Topic.Single,
      settings: Settings[F, E]
  ): Resource[F, MessageReader[F, E]] =
    mkPulsarReader[F, E](client, topic, settings)
      .map(mkMessageReader[F, E](_, settings.messageDecoder))

  // Builder-style abstract class instead of case class to allow for bincompat-friendly extension in future versions.
  sealed abstract class Settings[F[_], E] {
    val startMessageId: MessageId
    val readCompacted: Boolean
    val messageDecoder: Option[Array[Byte] => F[E]]
    val schema: Option[Schema[E]]

    /**
      * The Start message Id. `Latest` by default.
      */
    def withStartMessageId(_startMessageId: MessageId): Settings[F, E]

    /**
      * If enabled, the consumer will read messages from the compacted topic rather than reading the full message backlog
      * of the topic. This means that, if the topic has been compacted, the consumer will only see the latest value for
      * each key in the topic, up until the point in the topic message backlog that has been compacted. Beyond that
      * point, the messages will be sent as normal.
      *
      * <p>readCompacted can only be enabled subscriptions to persistent topics, which have a single active consumer
      * (i.e. failure or exclusive subscriptions). Attempting to enable it on subscriptions to a non-persistent topics
      * or on a shared subscription, will lead to the subscription call throwing a PulsarClientException.
      */
    def withReadCompacted: Settings[F, E]

    /**
      * Set the message decoder.
      *
      * Only in use when the Pulsar schema is not set.
      */
    protected[pulsar] def withMessageDecoder(f: Array[Byte] => F[E]): Settings[F, E]

    /**
      * Set the Pulsar schema.
      */
    protected[pulsar] def withSchema(_schema: Schema[E]): Settings[F, E]
  }

  /**
    * Consumer options such as subscription initial position and message logger.
    */
  object Settings {
    private case class SettingsImpl[F[_], E](
        startMessageId: MessageId,
        readCompacted: Boolean,
        messageDecoder: Option[Array[Byte] => F[E]],
        schema: Option[Schema[E]]
    ) extends Settings[F, E] {
      override def withStartMessageId(_startMessageId: MessageId): Settings[F, E] =
        copy(startMessageId = _startMessageId)

      override def withReadCompacted: Settings[F, E] =
        copy(readCompacted = true)

      // protected to ensure users don't set it and instead use the proper smart constructors
      protected[pulsar] override def withSchema(_schema: Schema[E]): Settings[F, E] =
        copy(schema = Some(_schema))

      protected[pulsar] override def withMessageDecoder(
          f: Array[Byte] => F[E]
      ): Settings[F, E] =
        copy(messageDecoder = Some(f))
    }

    def apply[F[_], E](): Settings[F, E] = SettingsImpl[F, E](
      startMessageId = MessageId.latest,
      readCompacted = false,
      messageDecoder = None,
      schema = None
    )
  }

}
