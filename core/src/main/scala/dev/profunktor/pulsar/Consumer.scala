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

import scala.jdk.CollectionConverters._
import scala.util.control.NoStackTrace

import dev.profunktor.pulsar.internal.FutureLift

import cats._
import cats.effect._
import cats.syntax.all._
import fs2._
import org.apache.pulsar.client.api.{ Consumer => JConsumer, _ }

trait Consumer[F[_], E] {

  /**
    * Acknowledge for a single message.
    */
  def ack(id: MessageId): F[Unit]

  /**
    * Negative acknowledge for a single message.
    */
  def nack(id: MessageId): F[Unit]

  /**
    * It consumes [[Consumer.Message]]s, which contain the ID and the PAYLOAD.
    *
    * If you don't need manual [[ack]]ing, consider using [[autoSubscribe]] instead.
    */
  def subscribe: Stream[F, Consumer.Message[E]]

  /**
    * It consumes [[Consumer.Message]]s, which contain the ID and the PAYLOAD, initially
    * resetting the subscription associated with this consumer to a specific message id.
    *
    * This means it would override the `SubscriptionInitialPosition` set in the settings.
    *
    * If you don't need manual [[ack]]ing, consider using [[autoSubscribe]] instead.
    */
  def subscribe(id: MessageId): Stream[F, Consumer.Message[E]]

  /**
    * Auto-ack subscription that consumes the message payload directly.
    */
  def autoSubscribe: Stream[F, E]

  /**
    * This operation fails when performed on a shared subscription where multiple
    * consumers are currently connected.
    *
    * If you do not need a durable subscription, consider using a
    * [[Subscription.Mode.NonDurable]] instead.
    */
  def unsubscribe: F[Unit]
}

private abstract class SchemaConsumer[F[_]: FutureLift: Sync, E](
    c: JConsumer[E],
    settings: Consumer.Settings[F, E]
) extends Consumer[F, E] {
  def subscribeInternal(
      autoAck: Boolean,
      seekId: Option[MessageId] = None
  ): Stream[F, Consumer.Message[E]] =
    Stream
      .exec(
        seekId.traverse_(id => FutureLift[F].futureLift(c.seekAsync(id)))
      )
      .append {
        Stream.repeatEval {
          FutureLift[F].futureLift(c.receiveAsync()).flatMap { m =>
            val e = m.getValue()

            settings.logger(e)(Topic.URL(m.getTopicName)) >>
              ack(m.getMessageId)
                .whenA(autoAck)
                .as(
                  Consumer.Message(
                    m.getMessageId,
                    MessageKey(m.getKey),
                    m.getProperties.asScala.toMap,
                    e
                  )
                )
          }
        }
      }
}

private abstract class ByteConsumer[F[_]: FutureLift: Sync, E](
    c: JConsumer[Array[Byte]],
    dec: Array[Byte] => F[E],
    settings: Consumer.Settings[F, E]
) extends Consumer[F, E] {
  def subscribeInternal(
      autoAck: Boolean,
      seekId: Option[MessageId] = None
  ): Stream[F, Consumer.Message[E]] =
    Stream
      .exec(
        seekId.traverse_(id => FutureLift[F].futureLift(c.seekAsync(id)))
      )
      .append {
        Stream.repeatEval {
          def go: F[Consumer.Message[E]] =
            FutureLift[F].futureLift(c.receiveAsync()).flatMap { m =>
              dec(m.getValue())
                .flatMap { e =>
                  settings.logger(e)(Topic.URL(m.getTopicName)) >>
                    ack(m.getMessageId)
                      .whenA(autoAck)
                      .as(
                        Consumer.Message(
                          m.getMessageId,
                          MessageKey(m.getKey),
                          m.getProperties.asScala.toMap,
                          e
                        )
                      )
                }
                .handleErrorWith { e =>
                  settings.decodingErrorHandler(e).flatMap {
                    case Consumer.OnFailure.Ack   => ack(m.getMessageId) >> go
                    case Consumer.OnFailure.Nack  => nack(m.getMessageId) >> go
                    case Consumer.OnFailure.Raise => e.raiseError
                  }
                }
            }
          go
        }
      }
}

object Consumer {

  case class Message[A](
      id: MessageId,
      key: MessageKey,
      properties: Map[String, String],
      payload: A
  )

  case class DecodingFailure(msg: String) extends Exception(msg) with NoStackTrace

  sealed trait OnFailure
  object OnFailure {
    case object Ack extends OnFailure
    case object Nack extends OnFailure
    case object Raise extends OnFailure
  }

  private def mkConsumer[F[_]: FutureLift: Sync, E](
      client: Pulsar.T,
      sub: Subscription,
      topic: Topic,
      settings: Settings[F, E]
  ): Resource[F, Consumer[F, E]] = {

    val acquire: F[Either[JConsumer[E], JConsumer[Array[Byte]]]] = {
      def configure[A](c: ConsumerBuilder[A]) = {
        val z = topic match {
          case s: Topic.Single => c.topic(s.url.value)
          case m: Topic.Multi  => c.topicsPattern(m.url.value.r.pattern)
        }
        settings.deadLetterPolicy
          .fold(z)(z.deadLetterPolicy)
          .readCompacted(settings.readCompacted)
          .subscriptionType(sub.`type`.pulsarSubscriptionType)
          .subscriptionName(sub.name.value)
          .subscriptionMode(sub.mode.pulsarSubscriptionMode)
          .subscriptionInitialPosition(settings.initial)
          .isAckReceiptEnabled(true)
          .subscribeAsync()
      }

      settings.schema match {
        case Some(s) =>
          FutureLift[F].futureLift(configure(client.newConsumer(s))).map(_.asLeft)
        case None =>
          FutureLift[F].futureLift(configure(client.newConsumer())).map(_.asRight)
      }
    }

    def release(ec: Either[JConsumer[E], JConsumer[Array[Byte]]]): F[Unit] =
      FutureLift[F]
        .futureLift(ec.fold(_.unsubscribeAsync(), _.unsubscribeAsync()))
        .attempt
        .whenA(settings.autoUnsubscribe)
        .flatMap(_ => FutureLift[F].futureLift(ec.fold(_.closeAsync(), _.closeAsync())))
        .void

    Resource
      .make(acquire)(release)
      .map {
        case Left(c) =>
          new SchemaConsumer[F, E](c, settings) {
            override def ack(id: MessageId): F[Unit] = Sync[F].delay(c.acknowledge(id))
            override def nack(id: MessageId): F[Unit] =
              Sync[F].delay(c.negativeAcknowledge(id))
            override def unsubscribe: F[Unit] =
              FutureLift[F].futureLift(c.unsubscribeAsync()).void
            override def subscribe: Stream[F, Message[E]] =
              subscribeInternal(autoAck = false)
            override def subscribe(id: MessageId): Stream[F, Consumer.Message[E]] =
              subscribeInternal(autoAck = false, seekId = Some(id))
            override def autoSubscribe: Stream[F, E] =
              subscribeInternal(autoAck = true).map(_.payload)
          }
        case Right(c) =>
          settings.messageDecoder.fold(
            throw new IllegalArgumentException(
              "Missing message decoder (used when pulsar schema is not set)"
            )
          ) { dec =>
            new ByteConsumer[F, E](c, dec, settings) {
              override def ack(id: MessageId): F[Unit] = Sync[F].delay(c.acknowledge(id))
              override def nack(id: MessageId): F[Unit] =
                Sync[F].delay(c.negativeAcknowledge(id))
              override def unsubscribe: F[Unit] =
                FutureLift[F].futureLift(c.unsubscribeAsync()).void
              override def subscribe: Stream[F, Message[E]] =
                subscribeInternal(autoAck = false)
              override def subscribe(id: MessageId): Stream[F, Consumer.Message[E]] =
                subscribeInternal(autoAck = false, seekId = Some(id))
              override def autoSubscribe: Stream[F, E] =
                subscribeInternal(autoAck = true).map(_.payload)
            }
          }
      }
  }

  /**
    * It creates a [[Consumer]] with the supplied message decoder (schema support disabled).
    *
    * A [[Topic]] can either be `Single` or `Multi` for multi topic subscriptions.
    *
    * Note that this does not create a subscription to any Topic,
    * you can use Consumer#subscribe for this purpose.
    */
  def make[F[_]: FutureLift: Sync, E](
      client: Pulsar.T,
      topic: Topic,
      sub: Subscription,
      messageDecoder: Array[Byte] => F[E]
  ): Resource[F, Consumer[F, E]] =
    make(client, topic, sub, messageDecoder, _ => OnFailure.Raise.pure[F].widen)

  /**
    * It creates a [[Consumer]] with the supplied message decoder (schema support disabled) and
    * decoding error handler.
    *
    * A [[Topic]] can either be `Single` or `Multi` for multi topic subscriptions.
    *
    * Note that this does not create a subscription to any Topic,
    * you can use Consumer#subscribe for this purpose.
    */
  def make[F[_]: FutureLift: Sync, E](
      client: Pulsar.T,
      topic: Topic,
      sub: Subscription,
      messageDecoder: Array[Byte] => F[E],
      decodingErrorHandler: Throwable => F[OnFailure]
  ): Resource[F, Consumer[F, E]] = {
    val settings = Settings[F, E]()
      .withMessageDecoder(messageDecoder)
      .withDecodingErrorHandler(decodingErrorHandler)
    mkConsumer(client, sub, topic, settings)
  }

  /**
    * It creates a [[Consumer]] with the supplied message decoder (schema support disabled) and settings.
    *
    * A [[Topic]] can either be `Single` or `Multi` for multi topic subscriptions.
    *
    * Note that this does not create a subscription to any Topic,
    * you can use Consumer#subscribe for this purpose.
    */
  def make[F[_]: FutureLift: Sync, E](
      client: Pulsar.T,
      topic: Topic,
      sub: Subscription,
      messageDecoder: Array[Byte] => F[E],
      settings: Settings[F, E]
  ): Resource[F, Consumer[F, E]] = {
    val _settings = settings
      .withMessageDecoder(messageDecoder)
      .withDecodingErrorHandler(_ => OnFailure.Raise.pure[F].widen)
    make(client, topic, sub, _settings)
  }

  /**
    * It creates a [[Consumer]] with the supplied message decoder (schema support disabled),
    * decoding error handler and settings.
    *
    * A [[Topic]] can either be `Single` or `Multi` for multi topic subscriptions.
    *
    * Note that this does not create a subscription to any Topic,
    * you can use Consumer#subscribe for this purpose.
    */
  def make[F[_]: FutureLift: Sync, E](
      client: Pulsar.T,
      topic: Topic,
      sub: Subscription,
      messageDecoder: Array[Byte] => F[E],
      decodingErrorHandler: Throwable => F[OnFailure],
      settings: Settings[F, E]
  ): Resource[F, Consumer[F, E]] = {
    val _settings = settings
      .withMessageDecoder(messageDecoder)
      .withDecodingErrorHandler(decodingErrorHandler)
    make(client, topic, sub, _settings)
  }

  /**
    * It creates a [[Consumer]] with the supplied pulsar schema.
    *
    * A [[Topic]] can either be `Single` or `Multi` for multi topic subscriptions.
    *
    * Note that this does not create a subscription to any Topic,
    * you can use Consumer#subscribe for this purpose.
    */
  def make[F[_]: FutureLift: Sync, E](
      client: Pulsar.T,
      topic: Topic,
      sub: Subscription,
      schema: Schema[E]
  ): Resource[F, Consumer[F, E]] =
    mkConsumer(client, sub, topic, Settings[F, E]().withSchema(schema))

  /**
    * It creates a [[Consumer]] with the supplied pulsar schema and settings.
    *
    * A [[Topic]] can either be `Single` or `Multi` for multi topic subscriptions.
    *
    * Note that this does not create a subscription to any Topic,
    * you can use Consumer#subscribe for this purpose.
    */
  def make[F[_]: FutureLift: Sync, E](
      client: Pulsar.T,
      topic: Topic,
      sub: Subscription,
      schema: Schema[E],
      settings: Settings[F, E]
  ): Resource[F, Consumer[F, E]] = {
    val _settings = settings.withSchema(schema)
    mkConsumer(client, sub, topic, _settings)
  }

  /**
    * It creates a [[Consumer]] with the supplied options, or a default value otherwise.
    *
    * A [[Topic]] can either be `Single` or `Multi` for multi topic subscriptions.
    *
    * Note that this does not create a subscription to any Topic,
    * you can use Consumer#subscribe for this purpose.
    */
  private def make[F[_]: FutureLift: Sync, E](
      client: Pulsar.T,
      topic: Topic,
      sub: Subscription,
      settings: Settings[F, E]
  ): Resource[F, Consumer[F, E]] =
    mkConsumer(client, sub, topic, settings)

  // Builder-style abstract class instead of case class to allow for bincompat-friendly extension in future versions.
  sealed abstract class Settings[F[_], E] {
    val initial: SubscriptionInitialPosition
    val logger: E => Topic.URL => F[Unit]
    val autoUnsubscribe: Boolean
    val readCompacted: Boolean
    val deadLetterPolicy: Option[DeadLetterPolicy]
    val messageDecoder: Option[Array[Byte] => F[E]]
    val decodingErrorHandler: Throwable => F[OnFailure]
    val schema: Option[Schema[E]]

    /**
      * The Subscription Initial Position. `Latest` by default.
      */
    def withInitialPosition(_initial: SubscriptionInitialPosition): Settings[F, E]

    /**
      * The logger action that runs on every message consumed. It does nothing by default.
      */
    def withLogger(_logger: E => Topic.URL => F[Unit]): Settings[F, E]

    /**
      * It will automatically `unsubscribe` from a topic whenever the consumer is closed.
      *
      * By default, this is turned off, as unsubscribing from a topic means deleting the subscription.
      *
      * This could be undesirable most of the time, specially when using Exclusive or Failover subscription
      * modes. Note that unsubscribing from a Shared subscription will always fail when multiple consumers
      * are connected.
      */
    def withAutoUnsubscribe: Settings[F, E]

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
      * Set dead letter policy for consumer.
      *
      * By default some message will redelivery so many times possible, even to the extent that it can be never stop.
      * By using dead letter mechanism messages will has the max redelivery count, when message exceeding the maximum
      * number of redeliveries, message will send to the Dead Letter Topic and acknowledged automatic.
      */
    def withDeadLetterPolicy(policy: DeadLetterPolicy): Settings[F, E]

    // protected to ensure users don't set it and instead use the proper smart constructors

    /**
      * Set the message decoder.
      *
      * Only in use when the Pulsar schema is not set.
      */
    protected[pulsar] def withMessageDecoder(f: Array[Byte] => F[E]): Settings[F, E]

    /**
      * Error handler for messages decoding errors.
      *
      * Normal actions are to log and either ack or nack the message, so the consumer can keep on processing messages.
      *
      * By default it re-raises the error in F.
      */
    protected[pulsar] def withDecodingErrorHandler(
        f: Throwable => F[OnFailure]
    ): Settings[F, E]

    /**
      * Set Pulsar schema (None by default).
      *
      * Messages will be automatically decoded by Pulsar consumers, so we don't get a chance to handle decoding failures,
      * which could terminate your stream.
      *
      * Notice that the decodingErrorHandler won't take effect if this is set.
      */
    protected[pulsar] def withSchema(schema: Schema[E]): Settings[F, E]
  }

  /**
    * Consumer options such as subscription initial position and message logger.
    */
  object Settings {
    private case class SettingsImpl[F[_]: Applicative, E](
        initial: SubscriptionInitialPosition,
        logger: E => Topic.URL => F[Unit],
        autoUnsubscribe: Boolean,
        readCompacted: Boolean,
        deadLetterPolicy: Option[DeadLetterPolicy],
        messageDecoder: Option[Array[Byte] => F[E]],
        decodingErrorHandler: Throwable => F[OnFailure],
        schema: Option[Schema[E]]
    ) extends Settings[F, E] {
      override def withInitialPosition(
          _initial: SubscriptionInitialPosition
      ): Settings[F, E] =
        copy(initial = _initial)

      override def withLogger(_logger: E => (Topic.URL => F[Unit])): Settings[F, E] =
        copy(logger = _logger)

      override def withAutoUnsubscribe: Settings[F, E] =
        copy(autoUnsubscribe = true)

      override def withReadCompacted: Settings[F, E] =
        copy(readCompacted = true)

      override def withDeadLetterPolicy(policy: DeadLetterPolicy): Settings[F, E] =
        copy(deadLetterPolicy = Some(policy))

      // protected to ensure users don't set it and instead use the proper smart constructors
      override protected[pulsar] def withMessageDecoder(
          f: Array[Byte] => F[E]
      ): Settings[F, E] =
        copy(messageDecoder = Some(f))

      override protected[pulsar] def withDecodingErrorHandler(
          f: Throwable => F[OnFailure]
      ): Settings[F, E] =
        copy(decodingErrorHandler = f)

      override protected[pulsar] def withSchema(schema: Schema[E]): Settings[F, E] =
        copy(schema = Some(schema))
    }

    def apply[F[_]: Applicative, E](): Settings[F, E] = SettingsImpl[F, E](
      SubscriptionInitialPosition.Latest,
      _ => _ => Applicative[F].unit,
      autoUnsubscribe = false,
      readCompacted = false,
      deadLetterPolicy = None,
      messageDecoder = None,
      decodingErrorHandler = _ => OnFailure.Raise.pure[F].widen,
      schema = None
    )
  }

}
