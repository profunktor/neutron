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
package transactions

import scala.concurrent.duration.FiniteDuration

import dev.profunktor.pulsar.internal.FutureLift

import cats.Apply
import cats.effect.kernel.Resource
import cats.effect.kernel.Resource.ExitCase
import cats.syntax.all._

object PulsarTx {
  def make[F[_]: Apply: FutureLift](
      client: Pulsar.T,
      timeout: FiniteDuration,
      logger: String => F[Unit]
  ): Resource[F, Tx] = {
    val acquire = FutureLift[F]
      .futureLift(
        client
          .newTransaction()
          .withTransactionTimeout(timeout.length, timeout.unit)
          .build()
      )
      .map(Tx.Underlying(_))

    val abort: (Tx, String) => F[Unit] = {
      case (Tx.Underlying(tx), msg) =>
        FutureLift[F].futureLift(tx.abort()) *> logger(
              s"TX: ${tx.getTxnID()} - Aborted ($msg)"
            )
    }

    val release: (Tx, ExitCase) => F[Unit] = {
      case (Tx.Underlying(tx), ExitCase.Succeeded) =>
        FutureLift[F].futureLift(tx.commit()) *>
            logger(s"TX: ${tx.getTxnID()} - Committed")
      case (tx, ExitCase.Errored(e)) =>
        abort(tx, e.getMessage())
      case (tx, ExitCase.Canceled) =>
        abort(tx, "interrupted - cancellation boundary")
    }

    Resource
      .makeCase(acquire)(release)
      .evalTap(tx => logger(s"TX: ${tx.getId} - Started"))
  }
}
