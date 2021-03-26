package co.ledger.lama.bitcoin.interpreter.services

import cats.data.{NonEmptyList, OptionT}
import cats.effect.{Clock, ContextShift, IO}
import co.ledger.lama.bitcoin.common.models.interpreter._
import co.ledger.lama.bitcoin.interpreter.Config.Db
import co.ledger.lama.bitcoin.interpreter.models.OperationToSave
import co.ledger.lama.bitcoin.interpreter.services.OperationQueries.{
  OpWithoutDetails,
  TransactionDetails
}
import co.ledger.lama.common.logging.DefaultContextLogging
import co.ledger.lama.common.models.{Sort, TxHash}
import doobie._
import doobie.implicits._
import fs2._

import java.util.UUID
import java.util.concurrent.TimeUnit

class OperationService(
    db: Transactor[IO],
    batchConcurrency: Db.BatchConcurrency
) extends DefaultContextLogging {

  def getOperations(
      accountId: UUID,
      blockHeight: Long,
      limit: Int,
      offset: Int,
      sort: Sort
  )(implicit cs: ContextShift[IO]): IO[GetOperationsResult] =
    for {
      opsWithTx <- OperationQueries
        .fetchOperations(accountId, blockHeight, sort, Some(limit + 1), Some(offset))
        .groupAdjacentBy(_.op.hash) // many operations by hash (RECEIVED AND SENT)
        .chunkN(OperationService.numberOfOperationsToBuildByQuery)
        .flatMap { ops =>
          val txHashes = ops.map { case (txHash, _) => txHash }.toNel

          val inputsAndOutputs = Stream
            .emits(txHashes.toList)
            .flatMap(txHashes =>
              OperationQueries
                .fetchTransactionDetails(accountId, sort, txHashes)
            )

          Stream
            .chunk(ops)
            .covary[ConnectionIO]
            .zip(inputsAndOutputs)
        }
        .transact(db)
        .through(makeOperation)
        .compile
        .toList

      total <- OperationQueries.countOperations(accountId, blockHeight).transact(db)

      // we get 1 more than necessary to know if there's more, then we return the correct number
      truncated = opsWithTx.size > limit

    } yield {
      val operations = opsWithTx.take(limit)
      GetOperationsResult(operations, total, truncated)
    }

  private lazy val makeOperation: Pipe[
    IO,
    (
        (TxHash, Chunk[OpWithoutDetails]),
        OperationQueries.TransactionDetails
    ),
    Operation
  ] =
    _.flatMap {
      case (
            (txHash, sentAndReceivedOperations),
            inputsWithOutputsByTxHash
          ) =>
        Stream
          .chunk(sentAndReceivedOperations)
          .takeWhile(_ => txHash == inputsWithOutputsByTxHash.txHash)
          .map { op =>
            operation(op, inputsWithOutputsByTxHash)
          }
    }

  def getOperation(
      accountId: Operation.AccountId,
      operationId: Operation.UID
  )(implicit cs: ContextShift[IO]): IO[Option[Operation]] = {

    val o = for {
      opWithTx <- OptionT(OperationQueries.findOperation(accountId, operationId))
      inputsWithOutputsWithTxHash <- OptionT(
        OperationQueries
          .fetchTransactionDetails(
            accountId.value,
            Sort.Ascending,
            NonEmptyList.one(opWithTx.op.hash)
          )
          .compile
          .last
      )
      if inputsWithOutputsWithTxHash.txHash == opWithTx.op.hash
    } yield operation(
      opWithTx,
      inputsWithOutputsWithTxHash
    )

    o.value.transact(db)
  }

  private def operation(
      emptyOperation: OpWithoutDetails,
      inputsWithOutputsByTxHash: TransactionDetails
  ) =
    Operation(
      uid = emptyOperation.op.uid,
      accountId = emptyOperation.op.accountId,
      hash = emptyOperation.op.hash.hex,
      transaction = TransactionView(
        id = emptyOperation.tx.id,
        hash = emptyOperation.tx.hash.hex,
        receivedAt = emptyOperation.tx.receivedAt,
        lockTime = emptyOperation.tx.lockTime,
        fees = emptyOperation.tx.fees,
        inputs = inputsWithOutputsByTxHash.inputs,
        outputs = inputsWithOutputsByTxHash.outputs,
        block = emptyOperation.tx.block,
        confirmations = emptyOperation.tx.confirmations
      ),
      operationType = emptyOperation.op.operationType,
      amount = emptyOperation.op.amount,
      fees = emptyOperation.op.fees,
      time = emptyOperation.op.time,
      blockHeight = emptyOperation.op.blockHeight
    )

  def deleteUnconfirmedOperations(accountId: UUID): IO[Int] =
    OperationQueries
      .deleteUnconfirmedOperations(accountId)
      .transact(db)

  def getUtxos(
      accountId: UUID,
      sort: Sort,
      limit: Int,
      offset: Int
  ): IO[GetUtxosResult] =
    for {
      utxos <- OperationQueries
        .fetchUTXOs(accountId, sort, Some(limit + 1), Some(offset))
        .transact(db)
        .compile
        .toList

      total <- OperationQueries.countUTXOs(accountId).transact(db)

    } yield {
      // We get 1 more than necessary to know if there's more, then we return the correct number
      GetUtxosResult(utxos.slice(0, limit), total, truncated = utxos.size > limit)
    }

  def getUnconfirmedUtxos(accountId: UUID): IO[List[Utxo]] =
    OperationQueries
      .fetchUnconfirmedUTXOs(accountId)
      .transact(db)
      .compile
      .toList

  def removeFromCursor(accountId: UUID, blockHeight: Long): IO[Int] =
    OperationQueries.removeFromCursor(accountId, blockHeight).transact(db)

  def compute(
      accountId: UUID
  )(implicit cs: ContextShift[IO], clock: Clock[IO]): Stream[IO, Operation.UID] =
    operationSource(accountId)
      .flatMap { op =>
        op.computeOperations
      }
      .through(saveOperationSink)

  private def operationSource(accountId: UUID) =
    OperationQueries
      .fetchTransactionAmounts(accountId)
      .transact(db)

  private def saveOperationSink(implicit
      cs: ContextShift[IO],
      clock: Clock[IO]
  ): Pipe[IO, OperationToSave, Operation.UID] = {

    val batchSize = Math.max(1000 / batchConcurrency.value, 100)

    in =>
      in.chunkN(batchSize)
        .parEvalMap(batchConcurrency.value) { operations =>
          for {
            start    <- clock.monotonic(TimeUnit.MILLISECONDS)
            savedOps <- OperationQueries.saveOperations(operations).transact(db)
            end      <- clock.monotonic(TimeUnit.MILLISECONDS)
            _ <- log.debug(
              s"${operations.head.map(_.uid)}: $savedOps operations saved in ${end - start} ms"
            )
          } yield operations.map(_.uid)

        }
        .flatMap(Stream.chunk)
  }
}

object OperationService {
  val numberOfOperationsToBuildByQuery = 5
}
