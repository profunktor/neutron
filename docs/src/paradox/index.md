# Neutron

Neutron is a purely functional [Apache Pulsar](https://pulsar.apache.org/) client for Scala, build on top of [fs2](https://fs2.io) and the [Java client for Pulsar](https://pulsar.apache.org/docs/en/client-libraries-java/).

@@@index

* [Reference](reference/Index.md)

@@@

It is published for Scala $scala-versions$. You can include it in your project by adding the following dependencies.

@@dependency[sbt,Maven,Gradle] {
  group="$org$" artifact="$neutron-core$" version="$version$"
  group2="$org$" artifact2="$neutron-circe$" version2="$version$"
  group3="$org$" artifact3="$neutron-function$" version3="$version$"
}

## Quick start

Here's a quick consumer / producer example using neutron. Note: both are fully asynchronous.

```scala mdoc:compile-only
import scala.concurrent.duration._

import dev.profunktor.pulsar._
import dev.profunktor.pulsar.schema.PulsarSchema

import cats.effect._
import fs2.Stream

object Demo extends IOApp.Simple {

  val config = Config.Builder.default

  val topic  =
    Topic.Builder
      .withName("my-topic")
      .withConfig(config)
      .withType(Topic.Type.NonPersistent)
      .build

  val subs =
    Subscription.Builder
      .withName("my-sub")
      .withType(Subscription.Type.Exclusive)
      .build

  val schema = PulsarSchema.utf8

  val resources: Resource[IO, (Consumer[IO, String], Producer[IO, String])] =
    for {
      pulsar   <- Pulsar.make[IO](config.url)
      consumer <- Consumer.make[IO, String](pulsar, topic, subs, schema)
      producer <- Producer.make[IO, String](pulsar, topic, schema)
    } yield consumer -> producer

  val run: IO[Unit] =
    Stream
      .resource(resources)
      .flatMap {
        case (consumer, producer) =>
          val consume =
            consumer
              .autoSubscribe
              .evalMap(IO.println)

          val produce =
            Stream
              .emit("test data")
              .covary[IO]
              .metered(3.seconds)
              .evalMap(producer.send_)

          consume.concurrently(produce)
      }
      .compile
      .drain

}
```
