# Schema

Neutron ships with support for [Pulsar schemas](https://pulsar.apache.org/docs/en/schema-get-started/), which are validated by Pulsar at the topic level, via an internal schema registry.

There are pros and cons to using the native support, so make sure you give the official documentation a thorough read before making a decision.

If you only want to play around with Pulsar to get familiar with it, you can either choose UTF-8 or the simple JSON support via `SchemaType.BYTES`, which have no validation whatsoever, as explained in the next sections.

## UTF-8

The simplest way to get started is to use the given UTF-8 encoding, which makes use of the native `Schema.BYTES`.

```scala mdoc:compile-only
import dev.profunktor.pulsar.schema.Schema
import dev.profunktor.pulsar.schema.utf8._

val schema = Schema[String] // summon instance
```

This brings into scope an `Schema[String]` instance, required to initialize consumers and producers. There's also a default instance `Schema[A]`, for any `cats.Inject[A, Array[Byte]]` instance (based on `Schema.BYTES` as well).

@@@ note
When using schemas, prefer to create the producer(s) before the consumer(s) for fail-fast semantics.
@@@

## JSON support

One of the most common communication protocols is JSON, and Neutron integrates with the Circe library to support it. We have a few alternatives here, which come from the `neutron-circe` dependency.

1. `SchemaType.BYTES`: This disables Pulsar schema support, which makes it faster, but there is no schema validation performed by Pulsar.
2. `SchemaType.JSON`: This enables Pulsar schema support, which means topics can be inspected by Pulsar Functions and so on, and it is validated by Pulsar at runtime, when creating producers and consumers.

The former is the easiest one, which only requires instances of Circe's `Decoder` and `Encoder` in scope. Just import `circe.bytes._`, as shown in the example below.

```scala mdoc:compile-only
import dev.profunktor.pulsar.schema.Schema
import dev.profunktor.pulsar.schema.circe.bytes._

import io.circe.{Decoder, Encoder}
import io.circe.generic.semiauto._

case class Person(age: Int, name: String)
object Person {
  implicit val jsonEncoder: Encoder[Person] = deriveEncoder
  implicit val jsonDecoder: Decoder[Person] = deriveDecoder
}

val schema = Schema[Person] // summon an instance
```

The latter is more complex, and there are two variations. The recommended one is based on semi-automatic derivation, as shown in the example below.

```scala mdoc:compile-only
import dev.profunktor.pulsar.schema.Schema
import dev.profunktor.pulsar.schema.circe._

import io.circe.{Decoder, Encoder}
import io.circe.generic.semiauto._

case class Event(id: Long, name: String)
object Event {
  implicit val jsonEncoder: Encoder[Event] = deriveEncoder
  implicit val jsonDecoder: Decoder[Event] = deriveDecoder

  implicit val jsonSchema: JsonSchema[Event] = JsonSchema.derive
}

val schema = Schema[Event] // summon an instance
```

It requires instances of `Decoder` and `Encoder`, and of `JsonSchema`, which expects an Avro schema, used internally by Pulsar.

A `JsonSchema` can be created directly using `JsonSchema.derive[A]`, which uses [avro4s](https://github.com/sksamuel/avro4s) under the hood. In fact, this is the recommended way but if you want to get something quickly up and running, you could also use auto-derivation.

```scala mdoc:compile-only
import dev.profunktor.pulsar.schema.Schema
import dev.profunktor.pulsar.schema.circe.auto._

import io.circe.{Decoder, Encoder}
import io.circe.generic.semiauto._

case class Foo(tag: String)
object Foo {
  implicit val jsonEncoder: Encoder[Foo] = deriveEncoder
  implicit val jsonDecoder: Decoder[Foo] = deriveDecoder
}

val schema = Schema[Foo] // summon an instance
```

Notice that `avro4s` is marked as `Provided`, meaning you need to explicitly add it to your classpath.

## Schema Compatibility Check Strategy

Whenever using schemas, make sure you fully understand the different [strategies](https://pulsar.apache.org/docs/en/schema-evolution-compatibility/#schema-compatibility-check-strategy), which only operate at the namespace level (e.g. see how integration tests are set up in the [run.sh](./run.sh) shell script).

For instance, when using the `BACKWARD` mode, a producer and consumer will fail to initialize if the schemas are incompatible, even if your custom JSON decoder can deserialize the previous model, the Pulsar broker doesn't know about it. E.g. say we have this model in our new application.

```scala
case class Event(uuid: UUID, value: String)
```

The generated Avro schema will look as follows.

```json
{
  "type" : "record",
  "name" : "Event",
  "namespace" : "dev.profunktor.pulsar.domain",
  "fields" : [ {
    "name" : "uuid",
    "type" : {
      "type" : "string",
      "logicalType" : "uuid"
    }
  }, {
    "name" : "value",
    "type" : "string"
  } ]
}
```

And later on, we introduce a breaking change in the model, adding a new **mandatory** field.

```scala
case class Event(uuid: UUID, value: String, code: Int)
```

This will be rejected at runtime, validated by Pulsar Schemas, when using the BACKWARD mode. The only changes allowed in this mode are:

- Add optional fields
- Delete fields

See the generated Avro schema below.

```json
{
  "type" : "record",
  "name" : "Event",
  "namespace" : "dev.profunktor.pulsar.domain",
  "fields" : [ {
    "name" : "uuid",
    "type" : {
      "type" : "string",
      "logicalType" : "uuid"
    }
  }, {
    "name" : "value",
    "type" : "string"
  }, {
    "name" : "code",
    "type" : "int"
  } ]
}
```

Instead, we should make the new field optional with a default value for this to work.

```scala
case class Event(uuid: UUID, value: String, code: Option[Int] = None)
```

This is now accepted by Pulsar since any previous `Event` still not consumed from a Pulsar topic can still be processed by the new consumers expecting the new schema.

```json
{
  "type" : "record",
  "name" : "Event",
  "namespace" : "dev.profunktor.pulsar.domain",
  "fields" : [ {
    "name" : "uuid",
    "type" : {
      "type" : "string",
      "logicalType" : "uuid"
    }
  }, {
    "name" : "value",
    "type" : "string"
  }, {
    "name" : "code",
    "type" : [ "null", "int" ],
    "default" : null
  } ]
}
```

See the difference with the previous schema? This one has a `default: null` in addition to the extra `null` type.
