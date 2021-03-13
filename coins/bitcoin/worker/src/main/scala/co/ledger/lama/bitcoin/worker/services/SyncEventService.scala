package co.ledger.lama.bitcoin.worker.services

import cats.effect.IO
import co.ledger.lama.bitcoin.common.models.explorer.Block
import co.ledger.lama.common.logging.IOLogging
import co.ledger.lama.common.models.messages.{ReportMessage, WorkerMessage}
import co.ledger.lama.common.utils.RabbitUtils
import co.ledger.lama.common.utils.RabbitUtils.AutoAckMessage
import dev.profunktor.fs2rabbit.interpreter.RabbitClient
import dev.profunktor.fs2rabbit.model.{ExchangeName, QueueName, RoutingKey}
import fs2.Stream
import io.circe.syntax._

trait SyncEventService {
  def consumeWorkerMessages: Stream[IO, AutoAckMessage[WorkerMessage[Block]]]
  def reportMessage(message: ReportMessage[Block]): IO[Unit]
}

class RabbitSyncEventService(
    rabbitClient: RabbitClient[IO],
    workerQueueName: QueueName,
    lamaExchangeName: ExchangeName,
    lamaRoutingKey: RoutingKey
) extends SyncEventService
    with IOLogging {

  def consumeWorkerMessages: Stream[IO, AutoAckMessage[WorkerMessage[Block]]] =
    RabbitUtils.createConsumer[WorkerMessage[Block]](rabbitClient, workerQueueName)

  private val publisher: Stream[IO, ReportMessage[Block] => IO[Unit]] =
    RabbitUtils
      .createPublisher[ReportMessage[Block]](rabbitClient, lamaExchangeName, lamaRoutingKey)

  def reportMessage(message: ReportMessage[Block]): IO[Unit] =
    publisher
      .evalMap(p => p(message) *> log.info(s"Published message: ${message.asJson.toString}"))
      .compile
      .drain

}
