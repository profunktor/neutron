# Reader

A reader allows you to "manually position" the offset within a topic and reading all messages from a specified message onward. For instance, you can start from a specific `MessageId`.

Neutron models it via a tagless algebra, as usual.

```scala mdoc:compile-only
import scala.concurrent.duration.FiniteDuration
import fs2.Stream

object Reader {
  sealed trait MessageAvailable
  object MessageAvailable {
    case object Yes extends MessageAvailable
    case object No extends MessageAvailable
  }
}

trait Reader[F[_], E] {
  def read: Stream[F, E]
  def read1: F[Option[E]]
  def readUntil(timeout: FiniteDuration): F[Option[E]]
  def messageAvailable: F[Reader.MessageAvailable]
}
```

There's also a `MessageReader` algebra, useful whenever you need more than the payload of the message, such as the `MessageId` and `MessageKey`.

## Creating a Reader

It provides a few constructors as both `Consumer` and `Producer` do for schema and message decoders, in addition to the following generic one.

```scala mdoc:compile-only
import dev.profunktor.pulsar._
import dev.profunktor.pulsar.Reader.Settings

import cats.effect._

def make[F[_]: Sync, E](
    client: Pulsar.T,
    topic: Topic.Single,
    settings: Settings[F, E]
): Resource[F, Reader[F, E]] = ???
```

If you're interested in a `MessageReader` instead, you can use `messageReader` instead of `make`.

Once we have a Pulsar client and a topic, we can proceed with the creation of a reader. If you missed that part, check out the @ref:[connection](../reference/Connection.md) and @ref:[topic](../reference/Topic.md) docs.

```scala mdoc
import dev.profunktor.pulsar._
import dev.profunktor.pulsar.schema.PulsarSchema

import cats.effect._

val schema = PulsarSchema.utf8

def creation(
    pulsar: Pulsar.T,
    topic: Topic.Single
): Resource[IO, Reader[IO, String]] =
  Reader.make[IO, String](pulsar, topic, schema)
```

## Reading messages

We can use any of the available `read` methods. E.g.

```scala mdoc
def simple(
    reader: Reader[IO, String]
): IO[Unit] =
  reader
   .read
   .evalMap(IO.println)
   .compile
   .drain
```

Or we can first ask whether there are available messages or not via `messageAvailable`.

## Reader settings

The reader constructor can also be customized with a few extra options. E.g.

```scala mdoc
import org.apache.pulsar.client.api.MessageId

val msgId: MessageId = null

val settings =
  Reader.Settings[IO, String]()
   .withStartMessageId(msgId)
   .withReadCompacted
   .withSchema(schema)

def custom(
    pulsar: Pulsar.T,
    topic: Topic.Single
): Resource[IO, Reader[IO, String]] =
  Reader.make(pulsar, topic, settings)
```

It is the responsibility of the application to know the specific `MessageId`, which internally represents a Ledger ID, Entry ID, and Partition ID.
