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

import java.nio.ByteBuffer

import scala.concurrent.{ ExecutionContext, Future }
import scala.jdk.CollectionConverters._
import scala.jdk.FutureConverters._
import scala.jdk.OptionConverters.RichOptional
import scala.reflect.ClassTag

import org.apache.pulsar.functions.api.{ WindowContext => JavaWindowContext }
import org.slf4j.Logger

import WindowContext._

final case class WindowContext(private val ctx: JavaWindowContext) {
  def tenant: Tenant                     = Tenant(ctx.getTenant)
  def namespace: Namespace               = Namespace(ctx.getNamespace)
  def functionName: FunctionName         = FunctionName(ctx.getFunctionName)
  def functionId: FunctionId             = FunctionId(ctx.getFunctionId)
  def instanceId: InstanceId             = InstanceId(ctx.getInstanceId)
  def numInstances: NumInstances         = NumInstances(ctx.getNumInstances)
  def functionVersion: FunctionVersion   = FunctionVersion(ctx.getFunctionVersion)
  def inputTopics: Seq[InputTopic]       = ctx.getInputTopics.asScala.toSeq.map(InputTopic(_))
  def outputTopic: OutputTopic           = OutputTopic(ctx.getOutputTopic)
  def outputSchemaType: OutputSchemaType = OutputSchemaType(ctx.getOutputSchemaType)

  def logger: Logger = ctx.getLogger

  def incrCounter(key: String, amount: Long): Unit = ctx.incrCounter(key, amount)
  def getCounter(key: String): Long                = ctx.getCounter(key)

  def putState(key: String, value: ByteBuffer): Unit = ctx.putState(key, value)
  def getState(key: String): Option[ByteBuffer]      = Option(ctx.getState(key))

  def userConfigMap: Map[String, AnyRef] = ctx.getUserConfigMap.asScala.toMap
  def userConfigValue[T: ClassTag](key: String): Option[T] =
    ctx.getUserConfigValue(key).toScala.collect { case x: T => x }

  def userConfigValueOrElse[T: ClassTag](key: String, defaultValue: T): T =
    userConfigValue[T](key).getOrElse(defaultValue)

  def recordMetric(metricName: String, value: Double): Unit =
    ctx.recordMetric(metricName, value)

  def publish[T](
      topicName: OutputTopic,
      obj: T,
      schemaOrSerdeClassName: String
  )(implicit ec: ExecutionContext): Future[Unit] =
    ctx.publish(topicName.value, obj, schemaOrSerdeClassName).asScala.map(_ => ())

  def publish[T](topicName: OutputTopic, obj: T)(
      implicit ec: ExecutionContext
  ): Future[Unit] =
    ctx.publish(topicName.value, obj).asScala.map(_ => ())
}

object WindowContext {
  final case class Tenant(value: String)
  final case class Namespace(value: String)
  final case class FunctionName(value: String)
  final case class FunctionId(value: String)
  final case class InstanceId(value: Int)
  final case class NumInstances(value: Int)
  final case class FunctionVersion(value: String)
  final case class InputTopic(value: String)
  final case class OutputTopic(value: String)
  final case class OutputSchemaType(value: String)
}
