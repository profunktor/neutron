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
  * - A previous sequence id (-1 if there are no previous messages).
  * - A previous payload (message).
  * - A new payload.
  *
  * An instance has to be contructed explicitly and pass it to `withDeduplication`
  * in the producer settings. It could be a typeclass but it makes things awkward
  * when deduplication is not required.
  *
  * You can either build one via either the `fromEq` or the `instance` constructors.
  */
trait SeqIdMaker[A] {
  def next(prevId: Long, prevPayload: Option[A], payload: A): Long
}

object SeqIdMaker {

  /**
    * Creates an instance using the given Eq[A] instance to determine whether
    * two values of type A are equal.
    */
  def fromEq[A: Eq]: SeqIdMaker[A] = new SeqIdMaker[A] {
    def next(prevId: Long, prevPayload: Option[A], payload: A): Long =
      prevPayload match {
        case Some(p) if p === payload => prevId
        case _                        => prevId + 5L
      }
  }

  /**
    * Creates an instance using the comparison function to determine whether
    * two values of type A are equal.
    */
  def instance[A](f: (A, A) => Boolean): SeqIdMaker[A] = fromEq[A](Eq.instance(f))
}
