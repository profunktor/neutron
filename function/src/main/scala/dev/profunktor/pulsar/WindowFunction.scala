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

import java.util

import scala.jdk.CollectionConverters._

import org.apache.pulsar.functions.api.{
  Record => JavaRecord,
  WindowContext => JavaWindowContext,
  WindowFunction => JavaWindowFunction
}

trait WindowFunction[In, Out] extends JavaWindowFunction[In, Out] {
  def handle(input: Seq[Record[In]], context: WindowContext): Out

  override def process(
      input: util.Collection[JavaRecord[In]],
      context: JavaWindowContext
  ): Out =
    handle(input.asScala.toSeq.map(Record(_)), WindowContext(context))
}
