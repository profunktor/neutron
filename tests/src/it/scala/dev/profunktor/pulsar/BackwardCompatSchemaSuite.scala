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

import java.util.UUID

import dev.profunktor.pulsar.domain._
import dev.profunktor.pulsar.schema.circe.JsonSchema

import cats.effect._
import cats.implicits._
import fs2.Stream
import org.apache.pulsar.client.api.PulsarClientException.IncompatibleSchemaException
import weaver.IOSuite

object BackwardCompatSchemaSuite extends IOSuite {

  val cfg = Config.Builder
    .withTenant("public")
    .withNameSpace("neutron")
    .withURL("pulsar://localhost:6650")
    .build

  override type Res = Pulsar.T
  override def sharedResource: Resource[IO, Res] = Pulsar.make[IO](cfg.url)

  val sub = (s: String) =>
    Subscription.Builder
      .withName(s)
      .withType(Subscription.Type.Failover)
      .build

  val topic = Topic.Builder
    .withName("json-backward")
    .withConfig(cfg)
    .build

  val batch = Producer.Batching.Disabled
  val shard = (_: Event) => ShardKey.Default

  val schema    = JsonSchema.make[Event]
  val schema_v2 = JsonSchema.make[Event_V2]
  val schema_v3 = JsonSchema.make[Event_V3]

  test("BACKWARD compatibility: producer sends old Event, Consumer expects Event_V2") {
    client =>
      val res: Resource[IO, (Consumer[IO, Event_V2], Producer[IO, Event])] =
        for {
          producer <- Producer.make[IO, Event](client, topic, schema)
          consumer <- Consumer.make[IO, Event_V2](client, topic, sub("circe"), schema_v2)
        } yield consumer -> producer

      ignore("FIXME: Figure out why this doesn't work after upgrading to Scala 3") >> (
        Ref.of[IO, Int](0),
        Deferred[IO, Event_V2]
      ).tupled.flatMap {
        case (counter, latch) =>
          Stream
            .resource(res)
            .flatMap {
              case (consumer, producer) =>
                val consume =
                  consumer.subscribe
                    .evalMap { msg =>
                      consumer.ack(msg.id) >>
                        counter.update(_ + 1) >>
                        counter.get.flatMap {
                          case n if n === 5 => latch.complete(msg.payload)
                          case _            => IO.unit
                        }
                    }

                val testEvent = Event(UUID.randomUUID(), "test")

                val events = List.fill(5)(testEvent)

                val produce =
                  Stream.eval {
                    events.traverse_(producer.send_) >> latch.get
                  }

                produce.concurrently(consume).evalMap { e =>
                  IO(expect.same(e, testEvent.toV2))
                }
            }
            .compile
            .lastOrError
      }
  }

  test(
    "BACKWARD compatibility: producer sends old Event, Consumer expects Event_V3, should break"
  ) { client =>
    val topic = Topic.Builder
      .withName("json-backward-broken")
      .withConfig(cfg)
      .build

    val res =
      for {
        producer <- Producer.make[IO, Event](client, topic, schema)
        consumer <- Consumer
                     .make[IO, Event_V3](client, topic, sub("broken-compat"), schema_v3)
      } yield consumer -> producer

    res.attempt.use {
      case Left(_: IncompatibleSchemaException) => IO.pure(success)
      case _                                    => IO(failure("Expected IncompatibleSchemaException"))
    }
  }

}
