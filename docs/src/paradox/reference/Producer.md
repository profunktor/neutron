# Producer

A [producer](https://pulsar.apache.org/docs/en/concepts-messaging/#producers) is a process that attaches to a topic and publishes messages to a Pulsar broker.

Neutron models it via a tagless algebra.

```scala mdoc:compile-only
import org.apache.pulsar.client.api.{ MessageId, ProducerStats }

trait Producer[F[_], E] {
  def send(msg: E): F[MessageId]
  def send(msg: E, properties: Map[String, String]): F[MessageId]
  def stats: F[ProducerStats]
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

## Deduplication

Pulsar supports [deduplication](https://pulsar.apache.org/docs/en/concepts-messaging/#message-deduplication) at the broker level.

In a nutshell, the deduplication mechanism is based on sequence ids, which can be set on every message on the underlying Java client.

To make things smoother, Neutron internally manages the creation of new sequence ids via the following interface.

```scala mdoc
trait SeqIdMaker[F[_]] {
  def make(lastSeqId: Long): F[Long]
}
```

Users are responsible for keeping track of their messages (usually by an `EventId` or so), and return `lastSeqId + 1` when the message is unique, or simply `lastSeqId` when it's a duplicate.

You may use the `instance` constructor as follows:

```scala mdoc
val seqIdMaker = SeqIdMaker.instance[IO] { lastSeqId =>
  IO.pure(lastSeqId + 1) // replace with your logic
}
```

To enable deduplication, we can use the following setting.

```scala mdoc
Producer.Settings[IO, String]().withDeduplication(seqIdMaker)
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
   .withMessageKey(s => MessageKey.Of(s.hashCode.toString))
   .withShardKey(s => ShardKey.Of(s.hashCode.toString.getBytes))
   .withLogger(e => url => IO.println(s"Message: $e, URL: $url"))
   .withUnsafeConf(_.autoUpdatePartitions(false))

def custom(
    pulsar: Pulsar.T,
    topic: Topic.Single
): Resource[IO, Producer[IO, String]] =
  Producer.make(pulsar, topic, encoder, settings)
```

The `withShardKey` option is quite useful when we want to publish messages based on certain property of your messages, e.g. an `EventId`. This applies when you use it together with `KeyShared` subscriptions, on the consumer side. Internally, this sets the `orderingKey` of every message.

On the other hand, the `withMessageKey` option sets the "partitioning key" of every message, used for compacted topics. Also bear in mind that if you use a `KeyShared` subscription but don't set the `orderingKey` (via `withShardKey`), the default `MessageKey` will be used.
