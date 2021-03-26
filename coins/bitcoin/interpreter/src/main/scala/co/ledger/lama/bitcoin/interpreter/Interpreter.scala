package co.ledger.lama.bitcoin.interpreter

import cats.data.OptionT
import cats.effect.{Clock, ContextShift, IO}
import co.ledger.lama.bitcoin.common.models.interpreter._
import co.ledger.lama.bitcoin.interpreter.Config.Db
import co.ledger.lama.bitcoin.interpreter.services._
import co.ledger.lama.common.logging.DefaultContextLogging
import co.ledger.lama.common.models._
import io.circe.syntax._
import fs2._
import doobie.Transactor

import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit
import co.ledger.lama.bitcoin.interpreter.models.AccountTxView

class Interpreter(
    publish: Notification => IO[Unit],
    db: Transactor[IO],
    maxConcurrent: Int,
    batchConcurrency: Db.BatchConcurrency
)(implicit cs: ContextShift[IO], clock: Clock[IO])
    extends DefaultContextLogging {

  val transactionService = new TransactionService(db, maxConcurrent)
  val operationService   = new OperationService(db, batchConcurrency)
  val flaggingService    = new FlaggingService(db)
  val balanceService     = new BalanceService(db, batchConcurrency)

  def saveTransactions: Pipe[IO, AccountTxView, Int] =
    transactionService.saveTransactions

  def getLastBlocks(
      accountId: UUID
  ): IO[List[BlockView]] =
    transactionService
      .getLastBlocks(accountId)
      .compile
      .toList

  def getOperations(
      accountId: UUID,
      blockHeight: Long,
      requestLimit: Int,
      requestOffset: Int,
      sort: Sort
  ): IO[GetOperationsResult] = {
    val limit  = if (requestLimit <= 0) 20 else requestLimit
    val offset = if (requestOffset < 0) 0 else requestOffset
    operationService.getOperations(accountId, blockHeight, limit, offset, sort)
  }

  def getOperation(
      accountId: Operation.AccountId,
      operationId: Operation.UID
  ): IO[Option[Operation]] =
    operationService.getOperation(accountId, operationId)

  def getUtxos(
      accountId: UUID,
      requestLimit: Int,
      requestOffset: Int,
      sort: Sort
  ): IO[GetUtxosResult] = {
    val limit  = if (requestLimit <= 0) 20 else requestLimit
    val offset = if (requestOffset < 0) 0 else requestOffset
    operationService.getUtxos(accountId, sort, limit, offset)
  }

  def getUnconfirmedUtxos(
      accountId: UUID
  ): IO[List[Utxo]] =
    operationService.getUnconfirmedUtxos(accountId)

  def removeDataFromCursor(
      accountId: UUID,
      blockHeight: Long
  ): IO[Int] = {
    for {
      txRes <- transactionService.removeFromCursor(accountId, blockHeight)
      _     <- log.info(s"Deleted $txRes operations")
      balancesRes <- balanceService.removeBalanceHistoryFromCursor(
        accountId,
        blockHeight
      )
      _ <- log.info(s"Deleted $balancesRes balances history")
    } yield txRes
  }

  def compute(
      accountId: UUID,
      addresses: List[AccountAddress],
      coin: Coin
  ): IO[Int] =
    for {
      balanceHistoryCount <- balanceService.getBalanceHistoryCount(accountId)

      start <- clock.monotonic(TimeUnit.MILLISECONDS)

      _ <- log.info(s"[$accountId] Flagging inputs and outputs belong")

      _ <- flaggingService.flagInputsAndOutputs(accountId, addresses)

      flaggingEnd <- clock.monotonic(TimeUnit.MILLISECONDS)

      _ <- operationService.deleteUnconfirmedOperations(accountId)

      cleanUnconfirmedEnd <- clock.monotonic(TimeUnit.MILLISECONDS)

      _ <- log.info(s"[$accountId] Computing operations")
      nbSavedOps <- operationService
        .compute(accountId)
        .through(
          notify(
            accountId,
            coin,
            balanceHistoryCount > 0
          )
        )
        .compile
        .foldMonoid

      computeOperationEnd <- clock.monotonic(TimeUnit.MILLISECONDS)

      _ <- log.info(s"[$accountId] Computing balance history")
      _ <- balanceService.computeNewBalanceHistory(accountId)

      end <- clock.monotonic(TimeUnit.MILLISECONDS)

      _ <- log.info(s"[$accountId] $nbSavedOps operations saved in ${end - start}ms ")
      _ <- log.info(
        s"[$accountId] flagging: ${flaggingEnd - start}ms, cleaning: ${cleanUnconfirmedEnd - flaggingEnd}ms, compute operations: ${computeOperationEnd - cleanUnconfirmedEnd}ms"
      )

      currentBalance <- balanceService.getCurrentBalance(accountId)

      _ <- publish(
        BalanceUpdatedNotification(
          accountId = accountId,
          coinFamily = CoinFamily.Bitcoin,
          coin = coin,
          currentBalance = currentBalance.asJson
        )
      )

    } yield nbSavedOps

  def notify(
      accountId: UUID,
      coin: Coin,
      shouldNotify: Boolean
  ): Pipe[IO, Operation.UID, Int] =
    _.parEvalMap(maxConcurrent) { opId =>
      OptionT
        .whenF(shouldNotify)(
          operationService.getOperation(Operation.AccountId(accountId), opId)
        )
        .foldF(IO.unit) { operation =>
          publish(
            OperationNotification(
              accountId = accountId,
              coinFamily = CoinFamily.Bitcoin,
              coin = coin,
              operation = operation.asJson
            )
          )
        } *> IO.pure(1)
    }

  def getBalance(
      accountId: UUID
  ): IO[CurrentBalance] =
    balanceService.getCurrentBalance(accountId)

  def getBalanceHistory(
      accountId: UUID,
      startO: Option[Instant],
      endO: Option[Instant],
      interval: Int
  ): IO[List[BalanceHistory]] = {

    if (startO.forall(start => endO.forall(end => start.isBefore(end))) && interval >= 0)
      balanceService.getBalanceHistory(
        accountId,
        startO,
        endO,
        if (interval > 0) Some(interval) else None
      )
    else
      IO.raiseError(
        new Exception(
          "Invalid parameters : 'start' should not be after 'end' and 'interval' should be positive"
        )
      )
  }

}
