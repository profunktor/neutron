# Connection

A connection to Apache Pulsar is an expensive operation, reason why it is recommended to create a single instance per application.

The main constructor returns a `cats.effect.Resource`, and is defined as follows.

```scala mdoc
import cats.effect._
import dev.profunktor.pulsar.Config.PulsarURL
import dev.profunktor.pulsar.Pulsar.Options

import org.apache.pulsar.client.api.{ PulsarClient => Underlying }

type T = Underlying

def make[F[_]: Sync](
    url: PulsarURL,
    opts: Options = Options()
): Resource[F, T] = ???
```

A `PulsarURL` can only be built using smart builders, as shown in the next section.

## Configuration

A default configuration is usually enough to get started locally.

```scala mdoc
import dev.profunktor.pulsar.Config

val config = Config.Builder.default
```

It sets `tenant=public`, `namespace=default`, and `url=pulsar://localhost:6650`. If would like to change any of these values, you can use the configuration builder. E.g.

```scala mdoc
val custom: Config =
  Config.Builder
    .withTenant("custom")
    .withNameSpace("services")
    .withURL("pulsar://custom.net:6650")
    .build
```

## Pulsar client

Once we have a configuration, we can proceed with the creation of a Pulsar client, necessary to create consumers, producers, and so on.

```scala mdoc
import dev.profunktor.pulsar._

Pulsar.make[IO](config.url)
```

### Connection Options

Via the second constructor argument, we can set a few client options, such as timeouts.

```scala mdoc
import scala.concurrent.duration._

val opts =
  Options()
    .withConnectionTimeout(45.seconds)
    .withOperationTimeout(30.minutes)

Pulsar.make[IO](config.url, opts)
```
