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
package domain

import java.util.UUID

import cats.Eq
import io.circe._
import io.circe.generic.semiauto._

case class Event_V3(uuid: UUID, value: String, thisFieldBreaksCompat: Int)

object Event_V3 {
  implicit val eq: Eq[Event_V3] = Eq.by(_.uuid)

  implicit val jsonEncoder: Encoder[Event_V3] = deriveEncoder
  implicit val jsonDecoder: Decoder[Event_V3] = deriveDecoder
}
