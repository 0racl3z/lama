package co.ledger.lama.bitcoin.worker

import cats.effect.{ContextShift, IO, Timer}
import cats.implicits._
import co.ledger.lama.bitcoin.common.clients.grpc.{InterpreterClient, KeychainClient}
import co.ledger.lama.bitcoin.common.clients.http.ExplorerClient
import co.ledger.lama.bitcoin.common.models.explorer.{
  Block,
  ConfirmedTransaction,
  UnconfirmedTransaction
}
import co.ledger.lama.bitcoin.worker.services.CursorStateService.AccountId
import co.ledger.lama.bitcoin.worker.services._
import co.ledger.lama.common.logging.DefaultContextLogging
import co.ledger.lama.common.models.Status.{Registered, Unregistered}
import co.ledger.lama.common.models.messages.{ReportMessage, WorkerMessage}
import co.ledger.lama.common.models.{AccountIdentifier, Coin, ReportError, ReportableEvent}
import fs2.Stream
import io.circe.syntax._

import java.util.UUID
import scala.math.Ordering.Implicits._
import scala.util.Try

class Worker(
    syncEventService: SyncEventService,
    keychainClient: KeychainClient,
    explorerClient: Coin => ExplorerClient,
    interpreterClient: InterpreterClient,
    cursorService: Coin => CursorStateService[IO]
) extends DefaultContextLogging {

  def run(implicit cs: ContextShift[IO], t: Timer[IO]): Stream[IO, Unit] =
    syncEventService.consumeWorkerMessages
      .evalMap { message =>
        val reportableEvent =
          message.event.status match {
            case Registered   => synchronizeAccount(message)
            case Unregistered => deleteAccount(message)
          }

        // In case of error, fallback to a reportable failed event.
        log.info(s"Received message: ${message.asJson.toString}") *>
          reportableEvent
            .handleErrorWith { error =>
              val failedEvent = message.event.asReportableFailureEvent(
                ReportError.fromThrowable(error)
              )

              log.error(s"Failed event: $failedEvent", error) *>
                IO.pure(failedEvent)
            }
            // Always report the event within a message at the end.
            .flatMap { reportableEvent =>
              syncEventService.reportMessage(
                ReportMessage(
                  account = message.account,
                  event = reportableEvent
                )
              )
            }
      }

  def synchronizeAccount(
      workerMessage: WorkerMessage[Block]
  )(implicit cs: ContextShift[IO], t: Timer[IO]): IO[ReportableEvent[Block]] = {

    val bookkeeper = Bookkeeper(
      new Keychain(keychainClient),
      explorerClient,
      interpreterClient
    )

    val account       = workerMessage.account
    val workableEvent = workerMessage.event

    val previousBlockState = workableEvent.cursor

    // sync the whole account per streamed batch
    for {

      keychainId <- IO.fromTry(Try(UUID.fromString(account.key)))

      addressesUsedByMempool <- bookkeeper
        .record[UnconfirmedTransaction](
          account.coin,
          account.id,
          keychainId,
          None
        )

      lastMinedBlock <- lastMinedBlock(account.coin)

      addresses <- Stream
        .emit(previousBlockState)
        .filter {
          case Some(previous) => previous < lastMinedBlock.block
          case None           => true
        }
        .evalTap(b => log.info(s"Syncing from cursor state: $b"))
        .evalMap(b => b.map(rewindToLastValidBlock(account, _)).sequence)
        .evalMap { lastValidBlock =>
          bookkeeper
            .record[ConfirmedTransaction](
              account.coin,
              account.id,
              keychainId,
              lastValidBlock.map(_.hash)
            )
        }
        .compile
        .toList
        .map(_.flatten)

      _ <- log.info(s"New cursor state: ${lastMinedBlock.block}")

      opsCount <- interpreterClient.compute(
        account.id,
        account.coin,
        (addresses ++ addressesUsedByMempool).distinct
      )

      _ <- log.info(s"$opsCount operations computed")

    } yield {
      // Create the reportable successful event.
      workableEvent.asReportableSuccessEvent(Some(lastMinedBlock.block))
    }
  }

  case class LastMinedBlock(block: Block)

  def lastMinedBlock(coin: Coin): IO[LastMinedBlock] =
    explorerClient(coin).getCurrentBlock.map(LastMinedBlock)

  private def rewindToLastValidBlock(account: AccountIdentifier, lastKnownBlock: Block): IO[Block] =
    for {
      lvb <- cursorService(account.coin).getLastValidState(AccountId(account.id), lastKnownBlock)
      _   <- log.info(s"Last valid block : $lvb")
      _ <-
        if (lvb.hash == lastKnownBlock.hash)
          // If the previous block is still valid, do not reorg
          IO.unit
        else {
          // remove all transactions and operations up until last valid block
          log.info(
            s"${lastKnownBlock.hash} is different than ${lvb.hash}, reorg is happening"
          ) *> interpreterClient.removeDataFromCursor(account.id, Some(lvb.height))
        }
    } yield lvb

  def deleteAccount(message: WorkerMessage[Block]): IO[ReportableEvent[Block]] =
    interpreterClient
      .removeDataFromCursor(message.account.id, None)
      .map(_ => message.event.asReportableSuccessEvent(None))
}
