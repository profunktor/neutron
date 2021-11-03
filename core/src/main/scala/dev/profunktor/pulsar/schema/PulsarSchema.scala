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

package dev.profunktor.pulsar.schema

import java.nio.charset.StandardCharsets.UTF_8

import dev.profunktor.pulsar.Consumer.DecodingFailure

import cats.Inject
import cats.implicits._
import org.apache.pulsar.client.api.Schema
import org.apache.pulsar.common.schema.SchemaInfo

object PulsarSchema {
  def fromInject[E](implicit I: Inject[E, Array[Byte]]): Schema[E] =
    new Schema[E] {
      override def clone(): Schema[E]              = this
      override def encode(message: E): Array[Byte] = I.inj(message)
      override def decode(bytes: Array[Byte]): E =
        I.prj(bytes)
          .getOrElse(throw new DecodingFailure(s"Could not decode bytes: $bytes"))
      override def getSchemaInfo(): SchemaInfo = Schema.BYTES.getSchemaInfo()
    }

  val utf8: Schema[String] =
    new Schema[String] {
      override def clone(): Schema[String]              = this
      override def encode(message: String): Array[Byte] = message.getBytes(UTF_8)
      override def decode(bytes: Array[Byte]): String =
        Either
          .catchNonFatal(new String(bytes, UTF_8))
          .getOrElse(throw new DecodingFailure(s"Could not decode bytes: $bytes"))
      override def getSchemaInfo(): SchemaInfo = Schema.BYTES.getSchemaInfo()
    }
}
