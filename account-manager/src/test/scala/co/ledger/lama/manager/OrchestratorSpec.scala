package co.ledger.lama.manager

import java.time.Instant
import java.util.UUID
import cats.effect.{ContextShift, IO, Timer}
import co.ledger.lama.common.models._
import co.ledger.lama.common.models.messages.{ReportMessage, WorkerMessage}
import co.ledger.lama.common.utils.IOAssertion
import co.ledger.lama.common.utils.RabbitUtils.AutoAckMessage
import dev.profunktor.fs2rabbit.model.DeliveryTag
import fs2.{Pipe, Stream}
import io.circe.JsonObject
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers

import scala.collection.mutable
import scala.concurrent.ExecutionContext
import scala.concurrent.duration._

class OrchestratorSpec extends AnyFlatSpecLike with Matchers {

  implicit val cs: ContextShift[IO] = IO.contextShift(ExecutionContext.global)
  implicit val t: Timer[IO]         = IO.timer(ExecutionContext.global)

  it should "succeed" in IOAssertion {
    val nbAccounts: Int            = 10
    val takeNbElements: Int        = 3
    val awakeEvery: FiniteDuration = 0.5.seconds
    val orchestrator               = new FakeOrchestrator(nbAccounts, awakeEvery)

    orchestrator.run(Some(takeNbElements)).compile.drain.map { _ =>
      orchestrator.tasks.foreach { t =>
        t.publishedWorkerMessages.keys should have size nbAccounts
        t.publishedWorkerMessages.values.foreach(_ should have size takeNbElements)
        t.reportedMessages should have size nbAccounts
        t.triggeredEvents should have size takeNbElements * nbAccounts
      }
    }
  }

}

class FakeOrchestrator(nbEvents: Int, override val awakeEvery: FiniteDuration)
    extends Orchestrator {

  val workerMessages: Seq[WorkerMessage[JsonObject]] = {
    val now = Instant.now()

    (1 to nbEvents).map { i =>
      val account =
        AccountIdentifier(s"xpub-$i", CoinFamily.Bitcoin, Coin.Btc, AccountGroup("TestGroup"))
      val event = WorkableEvent[JsonObject](
        accountId = account.id,
        syncId = UUID.randomUUID(),
        status = Status.Registered,
        cursor = None,
        error = None,
        time = now
      )

      WorkerMessage(account, event)
    }
  }

  val tasks: List[FakeSyncEventTask] = List(new FakeSyncEventTask(workerMessages))

}

class FakeSyncEventTask(workerMessages: Seq[WorkerMessage[JsonObject]]) extends SyncEventTask {

  var reportedMessages: mutable.Seq[ReportMessage[JsonObject]] = mutable.Seq.empty

  var publishedWorkerMessages: mutable.Map[UUID, List[WorkerMessage[JsonObject]]] =
    mutable.Map.empty

  var triggeredEvents: mutable.Seq[SyncEvent[JsonObject]] = mutable.Seq.empty

  def publishableWorkerMessages: Stream[IO, WorkerMessage[JsonObject]] =
    Stream.emits(workerMessages)

  def publishWorkerMessagePipe: Pipe[IO, WorkerMessage[JsonObject], Unit] =
    _.evalMap { message =>
      IO(
        publishedWorkerMessages.update(
          message.account.id,
          publishedWorkerMessages.getOrElse(message.account.id, List.empty) :+ message
        )
      )
    }

  def reportableMessages: Stream[IO, AutoAckMessage[ReportMessage[JsonObject]]] =
    Stream.emits(
      workerMessages.map(message =>
        AutoAckMessage(
          ReportMessage(message.account, message.event.asReportableSuccessEvent(None)), DeliveryTag(0L)
        )(_ => IO.unit)
      )
    )

  def reportMessagePipe: Pipe[IO, AutoAckMessage[ReportMessage[JsonObject]], Unit] =
    _.evalMap { e =>
      e.unwrap { msg =>
        IO {
          reportedMessages = reportedMessages :+ msg
        }
      }
    }

  def triggerableEvents: Stream[IO, TriggerableEvent[JsonObject]] =
    Stream.emits(
      workerMessages.map(message =>
        TriggerableEvent(
          message.account.id,
          message.event.syncId,
          Status.Synchronized,
          message.event.cursor,
          message.event.error,
          Instant.now()
        )
      )
    )

  def triggerEventsPipe: Pipe[IO, TriggerableEvent[JsonObject], Unit] =
    _.evalMap { e =>
      IO { triggeredEvents = triggeredEvents :+ e.nextWorkable }
    }
}
