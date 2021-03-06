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

/**
  * Dictates how `sequenceId`s (used for deduplication) are generated based on:
  *
  * - A previous sequence id (-1 if there are no previous messages).
  * - A message to be published you can use to compare with previous messages.
  *
  * Users are responsible for keeping track of their messages, and return (lastSeqId + 1) when
  * the message is unique, or simply `lastSeqId` when it's a duplicate.
  */
trait SeqIdMaker[F[_], A] {
  def make(lastSeqId: Long, currentMsg: A): F[Long]
}

object SeqIdMaker {

  /**
    * Creates an instance using the given 'make' function'.
    */
  def instance[F[_], A](f: (Long, A) => F[Long]): SeqIdMaker[F, A] =
    new SeqIdMaker[F, A] {
      def make(lastSeqId: Long, currentMsg: A): F[Long] = f(lastSeqId, currentMsg)
    }
}
