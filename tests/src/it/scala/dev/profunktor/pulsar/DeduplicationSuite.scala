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

import dev.profunktor.pulsar.schema.PulsarSchema

import cats.effect._
import cats.syntax.all._
import fs2.Stream
import weaver.IOSuite

object DeduplicationSuite extends IOSuite {

  val cfg = Config.Builder.default

  override type Res = Pulsar.T
  override def sharedResource: Resource[IO, Res] = Pulsar.make[IO](cfg.url)

  val sub =
    Subscription.Builder
      .withName("streaming")
      .withType(Subscription.Type.Shared)
      .build

  val topic = Topic.Builder
    .withName("dedup")
    .withConfig(cfg)
    .withType(Topic.Type.Persistent)
    .build

  val utf8 = PulsarSchema.utf8

  val pSettings =
    Producer
      .Settings[IO, String]()
      .withDeduplication(SeqIdMaker.fromEq[String])

  test("Producer deduplicates messages") { client =>
    val utf8 = PulsarSchema.utf8

    val res: Resource[IO, (Consumer[IO, String], Producer[IO, String])] =
      for {
        p <- Producer.make[IO, String](client, topic, utf8, pSettings)
        c <- Consumer.make[IO, String](client, topic, sub, utf8)
      } yield c -> p

    (IO.ref(List.empty[String]), IO.deferred[Unit]).tupled.flatMap {
      case (ref, latch) =>
        Stream
          .resource(res)
          .flatMap {
            case (c, p) =>
              val consume =
                c.subscribe.evalMap {
                  case Consumer.Message(id, _, _, payload) =>
                    for {
                      _ <- ref.update(_ :+ payload)
                      _ <- c.ack(id)
                      _ <- latch.complete(()).whenA(payload == "c")
                    } yield ()
                }

              val events   = List("a", "b", "b", "c")
              val expected = List("a", "b", "c")

              val produce =
                Stream.emits(events).evalMap(p.send_) ++ Stream.eval(latch.get)

              produce
                .concurrently(consume)
                .drain
                .append {
                  Stream.eval(ref.get).map { e =>
                    expect.same(e, expected)
                  }
                }
          }
          .compile
          .lastOrError
    }
  }

}
