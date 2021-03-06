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

import cats.Eq

sealed trait MessageKey
object MessageKey {
  final case class Of(value: String) extends MessageKey
  case object Empty extends MessageKey

  def apply(value: String): MessageKey =
    Option(value).filter(_.trim.nonEmpty).map(Of.apply).getOrElse(Empty)

  implicit val eq: Eq[MessageKey] = Eq.fromUniversalEquals
}
