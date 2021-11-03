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

import java.nio.charset.StandardCharsets.UTF_8
import java.util.UUID

import dev.profunktor.pulsar.domain._
import dev.profunktor.pulsar.schema.PulsarSchema
import dev.profunktor.pulsar.schema.circe.JsonSchema
import dev.profunktor.pulsar.domain.Outer.Inner

import cats.effect._
import cats.implicits._
import fs2.Stream
import io.circe.parser.{ decode => jsonDecode }
import io.circe.syntax._
import org.apache.pulsar.client.api.PulsarClientException.IncompatibleSchemaException
import weaver.IOSuite

object PulsarSuite extends IOSuite {

  val cfg = Config.Builder.default

  type Res = Pulsar.T
  override def sharedResource: Resource[IO, Res] = Pulsar.make[IO](cfg.url)

  val sub = (s: String) =>
    Subscription.Builder
      .withName(s)
      .withType(Subscription.Type.Failover)
      .build

  val topic = (s: String) =>
    Topic.Builder
      .withName(s)
      .withConfig(cfg)
      .build

  val batch = Producer.Batching.Disabled
  val shard = (_: Event) => ShardKey.Default

  val eSchema = JsonSchema.make[Event]

  val encoder: Event => Array[Byte] =
    _.asJson.noSpaces.getBytes(UTF_8)

  val decoder: Array[Byte] => IO[Event] =
    bs => IO.fromEither(jsonDecode[Event](new String(bs, UTF_8)))

  test(
    "A message is published and consumed successfully using Schema.BYTES via JSON-encoded strings (Circe)"
  ) { client =>
    val hpTopic = topic("happy-path-json-no-schema")

    val res: Resource[IO, (Consumer[IO, Event], Producer[IO, Event])] =
      for {
        producer <- Producer.make[IO, Event](client, hpTopic, encoder)
        consumer <- Consumer.make[IO, Event](client, hpTopic, sub("hps-circe"), decoder)
      } yield consumer -> producer

    Deferred[IO, Event].flatMap { latch =>
      Stream
        .resource(res)
        .flatMap {
          case (consumer, producer) =>
            val consume =
              consumer.subscribe
                .evalMap(msg => consumer.ack(msg.id) >> latch.complete(msg.payload))

            val testEvent = Event(UUID.randomUUID(), "test")

            val produce =
              Stream(testEvent)
                .covary[IO]
                .evalMap(producer.send)
                .evalMap(_ => latch.get)

            produce.concurrently(consume).evalMap { e =>
              IO(expect.same(e, testEvent))
            }
        }
        .compile
        .lastOrError
    }
  }

  test("A message is published and consumed successfully using Schema.JSON via Circe") {
    client =>
      val hpTopic = topic("happy-path-json")

      val res: Resource[IO, (Consumer[IO, Event], Producer[IO, Event])] =
        for {
          producer <- Producer.make[IO, Event](client, hpTopic, eSchema)
          consumer <- Consumer.make[IO, Event](client, hpTopic, sub("hp-circe"), eSchema)
        } yield consumer -> producer

      Deferred[IO, Event].flatMap { latch =>
        Stream
          .resource(res)
          .flatMap {
            case (consumer, producer) =>
              val consume =
                consumer.subscribe
                  .evalMap(msg => consumer.ack(msg.id) >> latch.complete(msg.payload))

              val testEvent = Event(UUID.randomUUID(), "test")

              val produce =
                Stream(testEvent)
                  .covary[IO]
                  .evalMap(producer.send)
                  .evalMap(_ => latch.get)

              produce.concurrently(consume).evalMap { e =>
                IO(expect.same(e, testEvent))
              }
          }
          .compile
          .lastOrError
      }
  }

  test(
    "A message is published and consumed successfully using Schema.BYTES via UTF_8 encoded strings"
  ) { client =>
    val hpTopic = topic("happy-path-bytes")

    val res: Resource[IO, (Consumer[IO, String], Producer[IO, String])] =
      for {
        producer <- Producer.make[IO, String](client, hpTopic, PulsarSchema.utf8)
        consumer <- Consumer.make[IO, String](
                     client,
                     hpTopic,
                     sub("hp-bytes"),
                     PulsarSchema.utf8
                   )
      } yield consumer -> producer

    Deferred[IO, String].flatMap { latch =>
      Stream
        .resource(res)
        .flatMap {
          case (consumer, producer) =>
            val consume =
              consumer.subscribe
                .evalMap(msg => consumer.ack(msg.id) >> latch.complete(msg.payload))

            val testMessage = "Hello Pulsar!"

            val produce =
              Stream(testMessage)
                .covary[IO]
                .evalMap(producer.send)
                .evalMap(_ => latch.get)

            produce.concurrently(consume).evalMap { e =>
              IO(expect.same(e, testMessage))
            }
        }
        .compile
        .lastOrError
    }
  }

  test("Incompatible schema types for consumer and producer") { client =>
    val dfTopic = topic("incompatible-types")

    val res: Resource[IO, (Consumer[IO, Event], Producer[IO, String])] =
      for {
        producer <- Producer.make[IO, String](client, dfTopic, PulsarSchema.utf8)
        consumer <- Consumer.make[IO, Event](client, dfTopic, sub("incompat"), eSchema)
      } yield consumer -> producer

    res.attempt.use {
      case Left(_: IncompatibleSchemaException) => IO.pure(success)
      case _                                    => IO(failure("Expected IncompatibleSchemaException"))
    }
  }

  test("A message with key is published and consumed successfully by the right consumer") {
    client =>
      val makeSub =
        (n: String) =>
          Subscription.Builder
            .withName(n)
            .withType(Subscription.Type.KeyShared)
            .build

      val settings =
        Producer
          .Settings[IO, Event]()
          .withShardKey(_.shardKey)
          .withBatching(batch)

      val res: Resource[
        IO,
        (Consumer[IO, Event], Consumer[IO, Event], Producer[IO, Event])
      ] =
        for {
          p1 <- Producer.make(client, topic("shared"), eSchema, settings)
          c1 <- Consumer.make[IO, Event](client, topic("shared"), makeSub("s1"), eSchema)
          c2 <- Consumer.make[IO, Event](client, topic("shared"), makeSub("s2"), eSchema)
        } yield (c1, c2, p1)

      (Ref.of[IO, List[Event]](List.empty), Ref.of[IO, List[Event]](List.empty)).tupled
        .flatMap {
          case (events1, events2) =>
            Stream
              .resource(res)
              .flatMap {
                case (c1, c2, producer) =>
                  val consume1 =
                    c1.subscribe
                      .evalMap(msg => c1.ack(msg.id) >> events1.update(_ :+ msg.payload))

                  val consume2 =
                    c2.subscribe
                      .evalMap(msg => c2.ack(msg.id) >> events2.update(_ :+ msg.payload))

                  val uuids = List(UUID.randomUUID(), UUID.randomUUID())

                  val events =
                    List.range(1, 6).map(x => Event(uuids(x % 2), "test"))

                  val produce =
                    Stream
                      .emits(events)
                      .covary[IO]
                      .evalMap(producer.send_)

                  val interrupter = {
                    val pred1: IO[Boolean] =
                      events1.get.map(
                        _.forall(
                          _.shardKey === ShardKey.Of(uuids(0).toString.getBytes(UTF_8))
                        )
                      )
                    val pred2: IO[Boolean] =
                      events2.get.map(
                        _.forall(
                          _.shardKey === ShardKey.Of(uuids(1).toString.getBytes(UTF_8))
                        )
                      )
                    Stream.eval((pred1, pred2).mapN { case (p1, p2) => p1 && p2 })
                  }

                  Stream(produce, consume1, consume2)
                    .parJoin(3)
                    .interruptWhen(interrupter)
              }
              .compile
              .drain
              .as(success)
        }
  }

  val fruitSchema = JsonSchema.make[Fruit]

  test("Support for JSONSchema with ADTs") { client =>
    val vTopic = topic("fruits-adt")

    val res: Resource[IO, (Consumer[IO, Fruit], Producer[IO, Fruit])] =
      for {
        producer <- Producer.make[IO, Fruit](client, vTopic, fruitSchema)
        consumer <- Consumer.make[IO, Fruit](client, vTopic, sub("fruits"), fruitSchema)
      } yield consumer -> producer

    res.use(_ => IO.pure(success))
  }

  val innerSchema = JsonSchema.make[Inner]

  test("Support for JSONSchema with class defined within an object") { client =>
    val vTopic = topic("not-today")

    val res: Resource[IO, (Consumer[IO, Inner], Producer[IO, Inner])] =
      for {
        producer <- Producer.make[IO, Inner](client, vTopic, innerSchema)
        consumer <- Consumer
                     .make[IO, Inner](client, vTopic, sub("outer-inner"), innerSchema)
      } yield consumer -> producer

    res.use(_ => IO.pure(success))
  }

}
