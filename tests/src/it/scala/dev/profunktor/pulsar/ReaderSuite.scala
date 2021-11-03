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

import cats.effect.{ IO, Resource }
import dev.profunktor.pulsar.PulsarSuite.topic
import dev.profunktor.pulsar.Reader.MessageAvailable
import dev.profunktor.pulsar.domain.Event
import dev.profunktor.pulsar.schema.circe.JsonSchema
import weaver.IOSuite

import java.util.UUID

object ReaderSuite extends IOSuite {
  override type Res = Pulsar.T
  override def sharedResource: Resource[IO, Res] =
    Pulsar.make[IO](Config.Builder.default.url)

  val schema = JsonSchema.make[Event]

  test("Reader can check if topic has messages") { client =>
    val hpTopic = topic("reader-test" + UUID.randomUUID())

    val resources = for {
      prod <- Producer.make[IO, Event](client, hpTopic, schema)
      reader <- Reader.make[IO, Event](client, hpTopic, schema)
    } yield prod -> reader

    resources
      .use {
        case (producer, reader) =>
          for {
            res1 <- reader.messageAvailable
            _ <- producer.send(Event(UUID.randomUUID(), "test"))
            res2 <- reader.messageAvailable
          } yield {
            expect.same(MessageAvailable.No, res1) &&
            expect.same(MessageAvailable.Yes, res2)
          }
      }
  }

  test("Reader can read a message if it exists") { client =>
    val hpTopic = topic("reader-test" + UUID.randomUUID())
    val event   = Event(UUID.randomUUID(), "test")

    val resources = for {
      prod <- Producer.make[IO, Event](client, hpTopic, schema)
      reader <- Reader.make[IO, Event](client, hpTopic, schema)
    } yield prod -> reader

    resources
      .use {
        case (producer, reader) =>
          for {
            res1 <- reader.read1
            _ <- producer.send(event)
            res2 <- reader.read1
          } yield {
            expect.same(None, res1) &&
            expect.same(Some(event), res2)
          }
      }
  }
}
