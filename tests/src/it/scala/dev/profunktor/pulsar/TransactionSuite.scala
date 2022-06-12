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

import scala.concurrent.duration._

import dev.profunktor.pulsar.schema.PulsarSchema
import dev.profunktor.pulsar.transactions.PulsarTx

import cats.effect._
import cats.syntax.all._
import fs2.Stream
import org.apache.pulsar.client.api.MessageId
import weaver.{ Expectations, IOSuite, SourceLocation }

object TransactionSuite extends IOSuite {

  val cfg = Config.Builder.default

  override type Res = Pulsar.T
  override def sharedResource: Resource[IO, Res] =
    Pulsar.make[IO](cfg.url, Pulsar.Settings().withTransactions)

  def subIn(id: Int) =
    Subscription.Builder
      .withName(s"tx-input-$id")
      .withType(Subscription.Type.Exclusive)
      .build

  def subOut(id: Int) =
    Subscription.Builder
      .withName(s"tx-output-$id")
      .withType(Subscription.Type.Exclusive)
      .build

  def topicIn(id: Int) =
    Topic.Builder
      .withName(s"tx-input-$id")
      .withConfig(cfg)
      .withType(Topic.Type.Persistent)
      .build

  def topicOut(id: Int) =
    Topic.Builder
      .withName(s"tx-output-$id")
      .withConfig(cfg)
      .withType(Topic.Type.Persistent)
      .build

  // Sets sendTimeout(0, TimeUnit.SECONDS), needed for transactional publishing.
  val pSettings = Producer.Settings[IO, String]().withDeduplication

  val utf8 = PulsarSchema.utf8

  def baseTest(
      id: Int,
      txResult: IO[Unit],
      client: Res,
      onError: (Throwable, SourceLocation) => Expectations
  ): IO[Expectations] = {
    val mkTx = PulsarTx.make[IO](client, 30.seconds, IO.println)

    val res =
      for {
        pi <- Producer.make[IO, String](client, topicIn(id), utf8, pSettings)
        po <- Producer.make[IO, String](client, topicOut(id), utf8, pSettings)
        ci <- Consumer.make[IO, String](client, topicIn(id), subIn(id), utf8)
        co <- Consumer.make[IO, String](client, topicOut(id), subOut(id), utf8)
      } yield (pi, po, ci, co)

    (
      IO.ref(List.empty[String]),
      IO.ref(Set.empty[MessageId]),
      IO.deferred[Unit]
    ).tupled.flatMap {
      case (ref, ids, latch) =>
        Stream
          .resource(res)
          .flatMap {
            case (pi, po, ci, co) =>
              val consumeInputs =
                ci.subscribe.evalMap {
                  case Consumer.Message(id, _, _, _, payload) =>
                    mkTx.use { tx =>
                      for {
                        _ <- ref.update(_ :+ payload)
                        _ <- ids.update(_ + id)
                        _ <- po.send_(s"$payload-out", tx)
                        _ <- txResult
                        _ <- ci.ack(id, tx)
                      } yield ()
                    }
                }

              val consumeOutputs =
                co.subscribe.evalMap {
                  case Consumer.Message(id, _, _, _, payload) =>
                    ref.update(_ :+ payload) *> co.ack(id) *>
                        latch.complete(()).whenA(payload == "c-out")
                }

              val events   = List("a", "b", "c")
              val expected = (events ++ events.map(x => s"$x-out")).sorted

              val produceInputs =
                Stream.emits(events).evalMap(pi.send_) ++ Stream.eval(latch.get)

              produceInputs
                .concurrently {
                  Stream(consumeInputs, consumeOutputs).parJoin(2)
                }
                .drain
                .append {
                  Stream.eval(ref.get).map { e =>
                    expect.same(e.sorted, expected)
                  }
                }
          }
          .compile
          .lastOrError
          .handleError(e => onError(e, implicitly))
    }
  }

  test("Consume-process-produce within a SUCCESSFUL transaction") { cli =>
    baseTest(
      id = 1,
      txResult = IO.unit,
      client = cli,
      onError = (e, pos) => failure(e.getMessage())(pos)
    )
  }

  test("Consume-process-produce within a FAILED transaction") { cli =>
    baseTest(
      id = 2,
      txResult = IO.raiseError(new Exception("abort tx")),
      client = cli,
      onError = (_, _) => success
    )
  }

}
