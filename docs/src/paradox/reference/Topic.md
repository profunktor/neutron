# Topic

A [Topic](https://pulsar.apache.org/docs/en/concepts-messaging/#topics) has a well-defined structure.

```
{persistent|non-persistent}://tenant/namespace/topic
```

To ensure correctness, it is necessary to build topics via smart builders.

## Single

A single topic is built via a `Topic.Builder`, as we have seen in the quick start section.

```scala mdoc
import dev.profunktor.pulsar._

val config = Config.Builder.default

val single: Topic.Single =
  Topic.Builder
    .withName("my-topic")
    .withConfig(config)
    .withType(Topic.Type.Persistent)
    .build
```

To learn more about configurations, check out the @ref:[Connection section](../reference/Connection.md).

## Multiple

Pulsar also supports subscriptions to multiple topics, which can be built using the same smart builder.

```scala mdoc
val multi: Topic.Multi =
  Topic.Builder
    .withNamePattern("events-*".r)
    .withConfig(config)
    .withType(Topic.Type.Persistent)
    .buildMulti
```

The `withNamePattern` method takes a regular expression as input.
