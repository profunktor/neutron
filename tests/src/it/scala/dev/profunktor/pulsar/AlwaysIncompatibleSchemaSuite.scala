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

import dev.profunktor.pulsar.domain._
import dev.profunktor.pulsar.schema.circe.JsonSchema

import cats.effect._
import org.apache.pulsar.client.api.PulsarClientException.IncompatibleSchemaException
import weaver.IOSuite

object AlwaysIncompatibleSchemaSuite extends IOSuite {

  val cfg = Config.Builder
    .withTenant("public")
    .withNameSpace("nope")
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
    .withName("json-always-incompatible")
    .withConfig(cfg)
    .build

  val batch = Producer.Batching.Disabled
  val shard = (_: Event) => ShardKey.Default

  val schema_v2 = JsonSchema.make[Event_V2]
  val schema    = JsonSchema.make[Event]

  test(
    "ALWAYS_INCOMPATIBLE schemas: producer sends new Event_V2, Consumer expects old Event"
  ) { client =>
    val res: Resource[IO, (Consumer[IO, Event], Producer[IO, Event_V2])] =
      for {
        producer <- Producer.make[IO, Event_V2](client, topic, schema_v2)
        consumer <- Consumer.make[IO, Event](client, topic, sub("circe"), schema)
      } yield consumer -> producer

    ignore("FIXME: Not working on Scala 3") >>
      res.attempt.use {
        case Left(_: IncompatibleSchemaException) => IO.pure(success)
        case _                                    => IO(failure("Expected IncompatibleSchemaException"))
      }
  }

}
