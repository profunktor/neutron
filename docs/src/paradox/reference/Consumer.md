# Consumer

A [consumer](https://pulsar.apache.org/docs/en/concepts-messaging/#consumers) is a process that attaches to a topic via a subscription, in order to receive messages.

Neutron models it via a tagless algebra.

```scala mdoc:compile-only
import dev.profunktor.pulsar._

import fs2.Stream
import org.apache.pulsar.client.api.MessageId

object Consumer {
  case class Message[A](id: MessageId, key: MessageKey, payload: A)
}

trait Consumer[F[_], E] {
  def ack(id: MessageId): F[Unit]
  def nack(id: MessageId): F[Unit]
  def subscribe: Stream[F, Consumer.Message[E]]
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

There is a smart constructor we can use to create a consumer, defined as follows.

```scala
def make[F[_]: Sync: FutureLift, E: Schema](
    client: Pulsar.T,
    topic: Topic,
    sub: Subscription,
    opts: Options[F, E] = null // default value does not work with generics
): Resource[F, Consumer[F, E]] = ???
```

Once we have a subscription, we can create a consumer, assuming we also have a pulsar connection and a topic.

If you missed that part, check out the @ref:[connection](../reference/Connection.md) and @ref:[topic](../reference/Topic.md) docs.

```scala mdoc
import dev.profunktor.pulsar.schema.utf8._

import cats.effect._

def creation(
    pulsar: Pulsar.T,
    topic: Topic
): Resource[IO, Consumer[IO, String]] =
  Consumer.make[IO, String](pulsar, topic, subs)
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
   .evalMap { case Consumer.Message(id, _, payload) =>
     process(payload) // pretend `process` might raise an error
       .flatMap(_ => consumer.ack(id))
       .handleErrorWith(e => IO.println(e) *> consumer.nack(id))
   }
   .compile
   .drain
```

It allows us to decide whether to ack or nack a message (it will be re-delivered by Pulsar).

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

## Consumer options

When creating a consumer, we can choose to customize the default options. E.g.

```scala mdoc
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

val opts =
  Consumer.Options[IO, String]()
   .withInitialPosition(SubscriptionInitialPosition.Earliest)
   .withLogger(e => url => IO.println(s"Message: $e, URL: $url"))
   .withAutoUnsubscribe
   .withReadCompacted
   .withDeadLetterPolicy(deadLetterPolicy)

def custom(
    pulsar: Pulsar.T,
    topic: Topic
): Resource[IO, Consumer[IO, String]] =
  Consumer.make[IO, String](pulsar, topic, subs, opts)
```
