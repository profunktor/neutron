# Producer

A [producer](https://pulsar.apache.org/docs/en/concepts-messaging/#producers) is a process that attaches to a topic and publishes messages to a Pulsar broker.

Neutron models it via a tagless algebra.

```scala mdoc:compile-only
import dev.profunktor.pulsar._
import org.apache.pulsar.client.api.MessageId

trait Producer[F[_], E] {
  def send(msg: E): F[MessageId]
  def send(msg: E, key: MessageKey): F[MessageId]
  def send(msg: E, properties: Map[String, String]): F[MessageId]
  def send(msg: E, key: MessageKey, properties: Map[String, String]): F[MessageId]
}
```

As well as the `send_` equivalent that discards the `MessageId` and returns `F[Unit]`. We will expand on its methods in the next few sections.

## Creating a Producer

It defines a few constructs, similarly as `Consumer` does. If we need Pulsar schema support, this is the constructor (also another one that takes in an extra argument for the producer settings):

```scala
def make[F[_]: FutureLift: Parallel: Sync, E](
    client: Pulsar.T,
    topic: Topic.Single,
    schema: Schema[E]
): Resource[F, Producer[F, E]] = ???
```

If we do not need Pulsar schema support, we need to provide a message encoder.

```scala
def make[F[_]: FutureLift: Parallel: Sync, E](
    client: Pulsar.T,
    topic: Topic.Single,
    messageEncoder: E => Array[Byte]
): Resource[F, Producer[F, E]] = ???
```

Check out all the available smart constructors either in the API or in the source code.

Once we have a connection and a topic, we can proceed with the creation of producer. If you missed that part, check out the @ref:[connection](../reference/Connection.md) and @ref:[topic](../reference/Topic.md) docs.

```scala mdoc
import dev.profunktor.pulsar._
import dev.profunktor.pulsar.schema.PulsarSchema

import cats.effect._

val schema = PulsarSchema.utf8

def creation(
    pulsar: Pulsar.T,
    topic: Topic.Single
): Resource[IO, Producer[IO, String]] =
  Producer.make[IO, String](pulsar, topic, schema)
```

## Publishing a message

We can publish a message via the `send` method, which returns a `MessageId` we could potentially use to store in the application's state. If you don't need it, prefer to use `send_` instead.

```scala mdoc
def simple(
    producer: Producer[IO, String]
): IO[Unit] =
  producer.send_("some-message")
```

## Sharding

The other two variations also take a `MessageKey` as argument, which is used for distributing messages.

```scala mdoc:compile-only
sealed trait MessageKey
object MessageKey {
  final case class Of(value: String) extends MessageKey
  case object Empty extends MessageKey
}
```

This could be useful if you want to publish a message to a specific shard at some point.

```scala mdoc
def shard(
    producer: Producer[IO, String]
): IO[Unit] = {
  val msg = "some-message"
  val key = MessageKey.Of(msg.hashCode.toString)
  producer.send_(msg, key)
}
```

However, if you always want to publish messages according to a specific key, prefer to use the `withShardKey` option, described in the next section.

## Deduplication

Pulsar supports [deduplication](https://pulsar.apache.org/docs/en/concepts-messaging/#message-deduplication) at the broker level.

In a nutshell, the deduplication mechanism is based on sequence ids, which can be set on every message on the underlying Java client.

To make things smoother, Neutron internally manages the creation of new sequence ids via the following interface.

```scala mdoc
trait SeqIdMaker[A] {
  def next(prevId: Long, prevPayload: Option[A], payload: A): Long
}
```

A default instance for any `A: Eq` can be constructed via the `fromEq` method, which compares the previous payload with the current payload. If they are equal, the same sequence id is returned. Otherwise, a `prevId + 1` is used.

```scala mdoc
val seqIdMaker = SeqIdMaker.fromEq[String]
```

This instance is usually good enough, as Pulsar only requires the next sequence id should be greater than the previous one. However, if for some reason you need a different implementation, you can write your own instance.

To enable deduplication, we can use the following setting.

```scala mdoc
Producer.Settings[IO, String]().withDeduplication(seqIdMaker, "producer-name-1")
```

The [DeduplicationSuite](https://github.com/profunktor/neutron/blob/main/tests/src/it/scala/dev/profunktor/pulsar/DeduplicationSuite.scala) showcases this feature (also see the `run.sh` script, where deduplication is enabled at the topic level).

## Producer settings

The producer constructor can also be customized with a few extra options. E.g.

```scala mdoc
import java.nio.charset.StandardCharsets.UTF_8

import scala.concurrent.duration._

val batching =
  Producer.Batching.Enabled(maxDelay = 5.seconds, maxMessages = 500)

val encoder: String => Array[Byte] =
 _.getBytes(UTF_8)

val settings =
  Producer.Settings[IO, String]()
   .withBatching(batching)
   .withShardKey(s => ShardKey.Of(s.hashCode.toString.getBytes))
   .withLogger(e => url => IO.println(s"Message: $e, URL: $url"))
   .withUnsafeConf(_.autoUpdatePartitions(false))

def custom(
    pulsar: Pulsar.T,
    topic: Topic.Single
): Resource[IO, Producer[IO, String]] =
  Producer.make(pulsar, topic, encoder, settings)
```

The `withShardKey` option is quite useful when we want to publish messages based on certain property of your messages, e.g. an `EventId`. This applies when you use it together with `KeyShared` subscriptions, on the consumer side.

When a `ShardKey` is defined, we don't need to provide a `MessageKey` manually and can just use the simple `send` and `send_` methods that take a single argument: the payload.
