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
import org.apache.pulsar.client.api.MessageId
import weaver.IOSuite

object SeekSubscribeSuite extends IOSuite {

  val cfg = Config.Builder.default

  override type Res = Pulsar.T
  override def sharedResource: Resource[IO, Res] = Pulsar.make[IO](cfg.url)

  val sub = (s: String) =>
    Subscription.Builder
      .withName(s)
      .withType(Subscription.Type.Shared)
      .build

  val topic = Topic.Builder
    .withName("seek-msg-id")
    .withConfig(cfg)
    .withType(Topic.Type.Persistent)
    .build

  val utf8 = PulsarSchema.utf8

  val events   = ('a' to 'z').toList.map(_.toString)
  val expected = ('h' to 'z').toList.map(_.toString)

  /**
    * In a few words, this test runs:
    *   - a producer that emits a list of letters from 'a' to 'z'.
    *   - a first consumer that persists the message id (offset) when it finds the letter 'g'.
    *   - a second consumer that reads the offset and rewinds the subscription to that point, expecting as a result,
    *     the consumption of the letters from 'h' to 'z'.
    */
  test("Subscribe(messageId) rewinds the subscription to the specific point") { client =>
    val res =
      for {
        p1 <- Producer.make[IO, String](client, topic, utf8)
        c1 <- Consumer.make[IO, String](client, topic, sub("fst"), utf8)
        c2 <- Consumer.make[IO, String](client, topic, sub("snd"), utf8)
      } yield (c1, c2, p1)

    (
      IO.deferred[MessageId],
      IO.deferred[String],
      IO.ref(List.empty[String]),
      IO.ref(List.empty[String]),
      IO.deferred[Either[Throwable, Unit]]
    ).tupled.flatMap {
      case (offset, latch, ref1, ref2, switch) =>
        Stream
          .resource(res)
          .flatMap {
            case (c1, c2, p1) =>
              val runC1 =
                c1.subscribe
                  .evalMap {
                    case msg if msg.payload == "z" =>
                      latch.complete(msg.payload).as(msg)
                    case msg if msg.payload == "g" =>
                      offset.complete(msg.id).as(msg)
                    case msg =>
                      IO.pure(msg)
                  }
                  .evalMap { msg =>
                    c1.ack(msg.id) *> ref1.update(_ :+ msg.payload)
                  }
                  .interruptWhen(switch)

              val runC2 =
                Stream
                  .eval(offset.get)
                  .flatMap { lastId =>
                    Stream.eval(latch.get).drain ++
                      c2.subscribe(lastId).evalMap { msg =>
                        c2.ack(msg.id) *> ref2.update(_ :+ msg.payload) *>
                          switch.complete(().asRight).whenA(msg.payload == "z")
                      }
                  }
                  .interruptWhen(switch)

              val produce =
                Stream
                  .emits(events)
                  .covary[IO]
                  .evalMap {
                    case msg if msg == "z" =>
                      p1.send(msg) *> latch.get
                    case msg =>
                      p1.send(msg)
                  }

              Stream(runC1, runC2, produce).parJoin(3)
          }
          .compile
          .drain *>
            (ref1.get, ref2.get).tupled.map {
              case (rs1, rs2) =>
                expect.same(rs1, events) && expect.same(rs2, expected)
            }
    }
  }

}
