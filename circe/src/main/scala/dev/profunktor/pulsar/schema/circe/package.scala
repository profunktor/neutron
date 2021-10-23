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

/**
  * The default instance gives you a schema with SchemaType.JSON, if you have
  * an Encoder, Decoder and JsonSchema in scope.
  *
  * {{{
  * import dev.profunktor.pulsar.schema.circe._
  * }}}
  *
  * A `JsonSchema` is usually derived from an Avro schema (see avro4s). If you would
  * like to have the `JsonSchema` derived for you (via avro4s auto derivation), you
  * can use the following import instead.
  *
  * {{{
  * import dev.profunktor.pulsar.schema.circe.auto._
  * }}}
  *
  * If you are not interested in Pulsar Schemas, you may prefer to deal with the
  * default SchemaType.BYTES, but still communicate via JSON. For this common use
  * case, you can use the following import instead, which only requires instances
  * of both Encoder and Decoder in scope.
  *
  * {{{
  * import dev.profunktor.pulsar.schema.circe.bytes._
  * }}}
  */
package object circe extends CirceDerivation {
  object auto extends AutoDerivation with CirceDerivation
  object bytes extends JsonAsBytes
}
