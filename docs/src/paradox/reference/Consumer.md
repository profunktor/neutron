# Consumer

A [consumer](https://pulsar.apache.org/docs/en/concepts-messaging/#consumers) is a process that attaches to a topic via a subscription, in order to receive messages.

Neutron models it via a tagless algebra.

```scala mdoc:compile-only
import dev.profunktor.pulsar._

import fs2.Stream
import org.apache.pulsar.client.api.MessageId

object Consumer {
  case class Message[A](
      id: MessageId,
      key: MessageKey,
      properties: Map[String, String],
      payload: A
  )
}

trait Consumer[F[_], E] {
  def ack(id: MessageId): F[Unit]
  def nack(id: MessageId): F[Unit]
  def subscribe: Stream[F, Consumer.Message[E]]
  def subscribe(id: MessageId): Stream[F, Consumer.Message[E]]
  def autoSubscribe: Stream[F, E]
  def unsubscribe: F[Unit]
}
```

We will expand on its methods in the next few sections.

## Subscriptions

Pulsar supports multiple [subscription modes](https://pulsar.apache.org/docs/en/concepts-messaging/#subscriptions), which can be created in Neutron via smart builders.

```scala mdoc
import dev.profunktor.pulsar._

val subs =
  Subscription.Builder
    .withName("my-sub")
    .withType(Subscription.Type.Shared)
    .build
```

There are four types of subscriptions: `Exclusive`, `Shared`, `KeyShared`, and `Failover`.

## Creating a Consumer

There are a few smart constructors we can use to create a consumer. If we want Pulsar schema support, we have the following one (also another one that takes an extra argument for the consumer settings).

```scala
import org.apache.pulsar.client.api.Schema

def make[F[_]: FutureLift: Sync, E](
    client: Pulsar.T,
    topic: Topic,
    sub: Subscription,
    schema: Schema[E]
): Resource[F, Consumer[F, E]] = ???
```

If we do not want Pulsar schema support, then we need to provide a message decoder, and ideally a decoding error handler, which are both functions.

```scala
def make[F[_]: FutureLift: Sync, E](
    client: Pulsar.T,
    topic: Topic,
    sub: Subscription,
    messageDecoder: Array[Byte] => F[E],
    decodingErrorHandler: Throwable => F[OnFailure] // defaults to Raise
): Resource[F, Consumer[F, E]] = ???
```

For example, an UTF-8 encoded string could be the following one:

```scala
import java.nio.charset.StandardCharsets.UTF_8

val utf8Decoder: Array[Byte] => IO[String] =
  bs => IO(new String(bs, UTF_8))
```

Or we could use a JSON decoder powered by Circe:

```scala
import io.circe.Decoder

def jsonDecoder[A: Decoder]: Array[Byte] => IO[A] =
  bs => IO.fromEither(io.circe.parser.decode[A](new String(bs, UTF_8)))
```

If we do not specify the decoding error handler, then the default is to re-raise the error when a message cannot be decoded. That's usually a sane default, but every case is different and you might want to `ack` or `nack` the message, which can be done as follows:

```scala
val handler: Throwable => IO[Consumer.OnFailure] =
  e => IO.println(s"[error] - ${e.getMessage}").as(Consumer.OnFailure.Nack)
```

There are many smart constructors to ensure you create a consumer with a valid state. Check out all of them in the API or source code.

Once we have a subscription, we can create a consumer, assuming we also have a pulsar connection and a topic.

If you missed that part, check out the @ref:[connection](../reference/Connection.md) and @ref:[topic](../reference/Topic.md) docs.

```scala mdoc
import dev.profunktor.pulsar.schema.PulsarSchema

import cats.effect._

val schema = PulsarSchema.utf8

def creation(
    pulsar: Pulsar.T,
    topic: Topic
): Resource[IO, Consumer[IO, String]] =
  Consumer.make[IO, String](pulsar, topic, subs, schema)
```

## Auto-subscription

This is the easiest way to get started with a consumer. Once a message is received, it will be automatically acknowledged (ack) by us. It is done via the `autoSubscribe` method, as shown below.

```scala mdoc
def auto(
    consumer: Consumer[IO, String]
): IO[Unit] =
  consumer
   .autoSubscribe
   .evalMap(IO.println)
   .compile
   .drain
```

In this case, `autoSubscribe` returns `Stream[IO, String]`, meaning we directly get the body of the message.

## Manual ack

In most serious applications, this should be the preferred way to consume messages, to avoid losing messages whenever the application fails after consuming a message.

For this purpose, we can use the `subscribe` method, as shown in the example below.

```scala mdoc
def process(payload: String): IO[Unit] =
  IO.println(s"Payload: $payload")

def manual(
    consumer: Consumer[IO, String]
): IO[Unit] =
  consumer
   .subscribe
   .evalMap { case Consumer.Message(id, _, _, payload) =>
     process(payload) // pretend `process` might raise an error
       .flatMap(_ => consumer.ack(id))
       .handleErrorWith(e => IO.println(e) *> consumer.nack(id))
   }
   .compile
   .drain
```

It allows us to decide whether to ack or nack a message (it will be re-delivered by Pulsar).

## Manual subscription

As shown in the section above, we can use the `subscribe` method to manually handle acknowledgements. Additionally, we have another variant of `subscribe` that takes a `MessageId` as an argument.

```scala
def subscribe(id: MessageId): Stream[F, Consumer.Message[E]]
```

This type of subscription will override the `SubscriptionInitialPosition` set in the settings and point this consumer to a specific message id --- internally done via `seekAsync`. This could be useful when we know exactly how far we want to rewind or where exactly we would like to start consuming.

## Unsubscribe

We can unsubscribe from a topic via the `unsubscribe` method, which implies deleting the subscription. We can do this whenever we are sure the process is over and we no longer need such subscription.

```scala mdoc
def finish(
    consumer: Consumer[IO, String]
): IO[Unit] =
  consumer
   .autoSubscribe
   .evalMap(IO.println)
   .onFinalize(consumer.unsubscribe)
   .compile
   .drain
```

This functionality can be enabled to be performed automatically via the `autoUnsubscribe` option.

## Consumer settings

When creating a consumer, we can choose to customize the default options. E.g.

```scala mdoc
import java.nio.charset.StandardCharsets.UTF_8

import org.apache.pulsar.client.api.{
  DeadLetterPolicy,
  SubscriptionInitialPosition
}

val deadLetterPolicy =
  DeadLetterPolicy
    .builder()
    .deadLetterTopic("foo")
    .maxRedeliverCount(100)
    .retryLetterTopic("bar")
    .build()

val utf8Decoder: Array[Byte] => IO[String] =
  bs => IO(new String(bs, UTF_8))

val handler: Throwable => IO[Consumer.OnFailure] =
  e => IO.println(s"[error] - ${e.getMessage}").as(Consumer.OnFailure.Nack)

val settings =
  Consumer.Settings[IO, String]()
   .withInitialPosition(SubscriptionInitialPosition.Earliest)
   .withLogger(e => url => IO.println(s"Message: $e, URL: $url"))
   .withAutoUnsubscribe
   .withReadCompacted
   .withDeadLetterPolicy(deadLetterPolicy)

def custom(
    pulsar: Pulsar.T,
    topic: Topic
): Resource[IO, Consumer[IO, String]] =
  Consumer.make[IO, String](
    pulsar,
    topic,
    subs,
    utf8Decoder,
    handler,
    settings
  )
```
