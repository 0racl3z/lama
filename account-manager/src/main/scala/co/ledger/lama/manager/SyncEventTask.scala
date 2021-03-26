package co.ledger.lama.manager

import cats.effect.{ContextShift, IO, Timer}
import io.circe.syntax._
import co.ledger.lama.common.logging.DefaultContextLogging
import co.ledger.lama.common.utils.RabbitUtils
import co.ledger.lama.common.models._
import co.ledger.lama.common.models.messages.{ReportMessage, WorkerMessage}
import co.ledger.lama.manager.config.CoinConfig
import com.redis.RedisClient
import dev.profunktor.fs2rabbit.interpreter.RabbitClient
import dev.profunktor.fs2rabbit.model.ExchangeName
import doobie.implicits._
import doobie.util.transactor.Transactor
import fs2.{Pipe, Stream}
import io.circe.JsonObject

import scala.concurrent.duration.FiniteDuration

trait SyncEventTask {

  // Source of worker messages to publish.
  def publishableWorkerMessages: Stream[IO, WorkerMessage[JsonObject]]

  // Publish events pipe transformation:
  // Stream[IO, WorkerMessage[JsonObject]] => Stream[IO, Unit].
  def publishWorkerMessagePipe: Pipe[IO, WorkerMessage[JsonObject], Unit]

  // Awake every tick, source worker messages then publish.
  def publishWorkerMessages(tick: FiniteDuration, stopAtNbTick: Option[Long] = None)(implicit
      t: Timer[IO]
  ): Stream[IO, Unit] =
    tickerStream(tick, stopAtNbTick) >> publishableWorkerMessages.through(publishWorkerMessagePipe)

  // Source of report messages to report.
  def reportableMessages: Stream[IO, ReportMessage[JsonObject]]

  // Report events pipe transformation:
  // Stream[IO, ReportMessage[JsonObject]] => Stream[IO, Unit].
  def reportMessagePipe: Pipe[IO, ReportMessage[JsonObject], Unit]

  // Source reportable messages then report the event of messages.
  def reportMessages: Stream[IO, Unit] =
    reportableMessages.through(reportMessagePipe)

  // Source of triggerable events.
  def triggerableEvents: Stream[IO, TriggerableEvent[JsonObject]]

  // Trigger events pipe transformation:
  // Stream[IO, TriggerableEvent[JsonObject]] => Stream[IO, Unit].
  def triggerEventsPipe: Pipe[IO, TriggerableEvent[JsonObject], Unit]

  // Awake every tick, source triggerable events then trigger.
  def trigger(tick: FiniteDuration)(implicit
      t: Timer[IO]
  ): Stream[IO, Unit] =
    tickerStream(tick) >> triggerableEvents.through(triggerEventsPipe)

  private def tickerStream(tick: FiniteDuration, stopAtNbTick: Option[Long] = None)(implicit
      t: Timer[IO]
  ): Stream[IO, FiniteDuration] = {
    val stream = Stream.awakeEvery[IO](tick)
    stopAtNbTick match {
      case Some(value) => stream.take(value) // useful to stop an infinite stream
      case None        => stream
    }
  }

}

class CoinSyncEventTask(
    workerExchangeName: ExchangeName,
    eventsExchangeName: ExchangeName,
    conf: CoinConfig,
    db: Transactor[IO],
    rabbit: RabbitClient[IO],
    redis: RedisClient
)(implicit cs: ContextShift[IO])
    extends SyncEventTask
    with DefaultContextLogging {

  // Fetch worker messages ready to publish from database.
  def publishableWorkerMessages: Stream[IO, WorkerMessage[JsonObject]] =
    Queries
      .fetchPublishableWorkerMessages(conf.coinFamily, conf.coin)
      .transact(db)

  // Publisher publishing to the worker exchange with routingKey = "coinFamily.coin".
  private val publisher =
    new WorkerMessagePublisher(
      redis,
      rabbit,
      workerExchangeName,
      conf.routingKey
    )

  // Publish messages to the worker exchange queue, mark event as published then insert.
  def publishWorkerMessagePipe: Pipe[IO, WorkerMessage[JsonObject], Unit] =
    _.evalMap { message =>
      publisher.enqueue(message) &>
        Queries
          .insertSyncEvent(message.event.asPublished)
          .transact(db)
          .void
    }

  // Consume messages to report from the events exchange queue.
  def reportableMessages: Stream[IO, ReportMessage[JsonObject]] =
    RabbitUtils
      .createAutoAckConsumer[ReportMessage[JsonObject]](
        rabbit,
        conf.queueName(eventsExchangeName)
      )

  // Insert reportable events in database and publish next pending event.
  def reportMessagePipe: Pipe[IO, ReportMessage[JsonObject], Unit] =
    _.evalMap { message =>
      Queries.insertSyncEvent(message.event).transact(db).void *>
        publisher.dequeue(message.account.id) *>
        log.info(s"Reported message: ${message.asJson.toString}")
    }

  // Fetch triggerable events from database.
  def triggerableEvents: Stream[IO, TriggerableEvent[JsonObject]] =
    Queries
      .fetchTriggerableEvents(conf.coinFamily, conf.coin)
      .transact(db)

  // From triggerable events, construct next events then insert.
  def triggerEventsPipe: Pipe[IO, TriggerableEvent[JsonObject], Unit] =
    _.evalMap { e =>
      Queries.insertSyncEvent(e.nextWorkable).transact(db).void *>
        log.info(s"Next event: ${e.nextWorkable.asJson.toString}")
    }

}
