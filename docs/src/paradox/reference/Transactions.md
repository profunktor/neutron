# Transactions

Pulsar supports [transactions](https://pulsar.apache.org/docs/en/txn-why/) to further enable event streaming applications to consume, process, and produce messages in one atomic operation.

Notice that the broker needs to be configured to support transactions (disabled by default).

```conf
transactionCoordinatorEnabled=true
```

Naturally, Neutron models a transaction as `Resource[F, Tx]`, where `Tx` is a custom interface that hides the underlying Java Transaction instance to avoid user mistakes.

```scala
sealed trait Tx {
  def getId: TxnID
}
```

## Working with transactions

The first step is to enable transactions on the Pulsar client.

```scala mdoc
import cats.effect._
import dev.profunktor.pulsar._

val cfg = Config.Builder.default

val mkClient =
  Pulsar.make[IO](cfg.url, Pulsar.Settings().withTransactions)
```

Next, we can create a transactional resource.

```scala mdoc
import scala.concurrent.duration._
import dev.profunktor.pulsar.transactions.{ PulsarTx, Tx }

mkClient.use { cli =>
  val mkTx = PulsarTx.make[IO](
    client = cli,
    timeout = 30.seconds,
    logger = str => IO.println(str)
  )

  mkTx.use { tx =>
    // atomic transactions here
    IO.println(s"tx-id: ${tx.getId}")
  }
}
```

Now we are ready to use the transaction for some atomic operations. Once we `use` the resource, the transaction will begin. From this point, we can consume, process, and produce messages in a transactional fashion.

The [TransactionSuite](https://github.com/profunktor/neutron/blob/main/tests/src/it/scala/dev/profunktor/pulsar/TransactionSuite.scala) showcases this feature in detail.

In a nutshell, we have two producers and two consumers in total, for inputs and outputs, respectively.

The first producer `pi` produces inputs that the `ci` consumer reads. These inputs are processed and the results are published as outputs by the `po` producer. Lastly, the `co` consumer would read these outputs.

The atomic operations happen between reading inputs and publishing outputs. To do so, the `ci` consumer keeps track of the `MessageId`s in memory, to then acknowledge them once we are done with the transaction.

```scala
val consumeInputs =
  ci.subscribe.evalMap {
    case Consumer.Message(id, _, _, payload) =>
      for {
        _ <- ref.update(_ :+ payload)
        _ <- ids.update(_ + id)
        _ <- po.send_(s"$payload-out")
      } yield ()
  }
```

We accumulate `MessageId`s in a local `ids` ref. Here's the inputs producer:

```scala
val produceInputs =
  Stream.emits(events).evalMap(pi.send_) ++ Stream.eval {
    latch.get *> ids.get.flatMap(_.toList.traverse_(ci.ack(_, tx)))
  }
```

Once the producer finishes, it proceeds to atomically acknowledge the `MessageId`s we have in memory. To do so, we use the special `ack` method that also takes a `Tx` as argument.

That `latch.get` is only there to synchronize the order in which things terminate in the test suite. Your use case may be handled differently.

When the resource scope ends, the transaction will be committed. If we wanted to abort the transaction instead, all we need is to raise an error. E.g.

```scala
latch.get *> IO.raiseError(new Exception("Abort tx"))
```

This error should be handled outside the resource scope to avoid crashing the entire program.

## Summary

In short, it all boils down to the following operations.

- Enable transactions support on the broker (via `broker.conf` or `standalone.conf`).
- Enable transactions support on the client (via `withTransactions` on settings).
- Acknowledge messages via `ack(id, tx)` after the processing and publishing of messages is done, to guarantee one atomic operation.
- Raise an error within the `use` block of the transaction to abort. Exit the `use` block to commit the transaction (done automatically).
