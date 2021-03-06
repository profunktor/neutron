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

import java.nio.charset.StandardCharsets.UTF_8
import java.util.UUID

import cats.Eq
import io.circe._
import io.circe.generic.semiauto._

case class Event(uuid: UUID, value: String) {
  def shardKey: ShardKey = ShardKey.Of(uuid.toString.getBytes(UTF_8))
  def toV2: Event_V2     = Event_V2(uuid, value, None)
}

object Event {
  implicit val eq: Eq[Event] = Eq.by(_.uuid)

  implicit val jsonEncoder: Encoder[Event] = deriveEncoder
  implicit val jsonDecoder: Decoder[Event] = deriveDecoder
}
