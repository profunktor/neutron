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

package dev.profunktor.pulsar.internal

import java.util.concurrent._

import cats.effect.kernel.Async

import cats.effect.std.Semaphore

private[pulsar] trait FutureLift[F[_]] {
  def futureLift[A](fa: => CompletableFuture[A]): F[A]
  def semaphore: F[Semaphore[F]]
}

private[pulsar] object FutureLift {
  def apply[F[_]: FutureLift]: FutureLift[F] = implicitly

  implicit def forAsync[F[_]: Async]: FutureLift[F] =
    new FutureLift[F] {
      def futureLift[A](fa: => CompletableFuture[A]): F[A] =
        Async[F].fromCompletableFuture(Async[F].delay(fa))
      def semaphore: F[Semaphore[F]] = Semaphore[F](1)
    }
}
