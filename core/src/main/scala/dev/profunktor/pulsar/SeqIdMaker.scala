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

import cats.kernel.Eq
import cats.syntax.eq._

/**
  * Dictates how `sequenceId`s (used for deduplication) are generated based on:
  *
  * - A previous sequence id.
  * - A previous payload (message).
  * - A new payload.
  *
  * There is a default instance for any `A: Eq`.
  */
trait SeqIdMaker[A] {
  def next(prevId: Long, prevPayload: Option[A], payload: A): Long
}

object SeqIdMaker {
  def apply[A: SeqIdMaker]: SeqIdMaker[A] = implicitly

  implicit def forEq[A: Eq]: SeqIdMaker[A] = new SeqIdMaker[A] {
    def next(prevId: Long, prevPayload: Option[A], payload: A): Long =
      prevPayload match {
        case Some(p) if p === payload => prevId
        case _                        => prevId + 1L
      }
  }
}
