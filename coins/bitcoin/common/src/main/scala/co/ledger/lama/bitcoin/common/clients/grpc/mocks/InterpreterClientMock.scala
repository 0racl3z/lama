package co.ledger.lama.bitcoin.common.clients.grpc.mocks

import cats.effect.IO
import co.ledger.lama.bitcoin.common.clients.grpc.InterpreterClient
import co.ledger.lama.bitcoin.common.models.interpreter._
import co.ledger.lama.common.models.{Account, Sort}
import java.time.Instant
import java.util.UUID

import scala.collection.mutable
import fs2._

class InterpreterClientMock extends InterpreterClient {

  var savedTransactions: mutable.Map[UUID, List[TransactionView]] = mutable.Map.empty
  var savedUnconfirmedTransactions: mutable.ArrayDeque[(UUID, List[TransactionView])] =
    mutable.ArrayDeque.empty
  var transactions: mutable.Map[UUID, List[TransactionView]] = mutable.Map.empty
  var operations: mutable.Map[UUID, List[Operation]]         = mutable.Map.empty

  def saveUnconfirmedTransactions(accountId: UUID, txs: List[TransactionView]): IO[Int] =
    IO.pure {
      savedUnconfirmedTransactions += accountId -> txs
      txs.size
    }

  def saveTransactions(accountId: UUID): Pipe[IO, TransactionView, Unit] =
    _.chunks.evalMap { chunk =>
      val txs = chunk.toList

      savedTransactions.update(
        accountId,
        txs.filter(_.block.isDefined) ::: savedTransactions.getOrElse(accountId, Nil)
      )

      val unconfirmed = txs.filterNot(_.block.isDefined)
      if (unconfirmed.nonEmpty)
        saveUnconfirmedTransactions(accountId, unconfirmed)

      IO.unit
    }

  def getSavedTransaction(accountId: UUID): List[TransactionView] = savedTransactions
    .getOrElse(
      accountId,
      List.empty
    )
    .distinctBy(_.id)

  def removeDataFromCursor(
      accountId: UUID,
      blockHeightCursor: Option[Long],
      followUpId: UUID
  ): IO[Int] = {
    savedTransactions.update(
      accountId,
      savedTransactions(accountId)
        .filter(tx => tx.block.map(_.height).getOrElse(0L) < blockHeightCursor.getOrElse(0L))
    )

    transactions.update(
      accountId,
      transactions(accountId)
        .filter(tx => tx.block.exists(_.height < blockHeightCursor.getOrElse(0L)))
    )

    operations.update(
      accountId,
      operations(accountId)
        .filter(op => op.transaction.block.exists(_.height < blockHeightCursor.getOrElse(0L)))
    )

    IO.pure(0)
  }

  def getLastBlocks(accountId: UUID): IO[List[BlockView]] = {
    val lastBlocks: List[BlockView] = savedTransactions(accountId)
      .collect { case TransactionView(_, _, _, _, _, _, _, Some(block), _) =>
        BlockView(
          block.hash,
          block.height,
          block.time
        )
      }
      .distinct
      .sortBy(_.height)(Ordering[Long].reverse)

    IO(lastBlocks)
  }

  def compute(
      account: Account,
      syncId: UUID,
      addresses: List[AccountAddress]
  ): IO[Int] = {

    val txViews = savedTransactions
      .getOrElse(account.id, List.empty)

    val computedTxViews = txViews.map { tx =>
      tx.copy(
        outputs = tx.outputs.map { o =>
          val addressO = addresses.find(_.accountAddress == o.address)
          o.copy(
            changeType = addressO.map(_.changeType),
            derivation = addressO.map(_.derivation)
          )
        },
        inputs = tx.inputs.map { i =>
          i.copy(
            derivation = addresses.find(_.accountAddress == i.address).map(_.derivation)
          )
        }
      )
    }

    transactions.update(
      account.id,
      computedTxViews
    )

    val operationToSave = transactions(account.id).flatMap { tx =>
      val inputAmount =
        tx.inputs.filter(i => addresses.exists(_.accountAddress == i.address)).map(_.value).sum
      val outputAmount = tx.outputs
        .filter(o => o.belongs && o.changeType.contains(ChangeType.External))
        .map(_.value)
        .sum
      val changeAmount = tx.outputs
        .filter(o => o.belongs && o.changeType.contains(ChangeType.Internal))
        .map(_.value)
        .sum

      (inputAmount > 0, outputAmount > 0) match {
        // only input, consider changeAmount as deducted from spent
        case (true, false) =>
          List(makeOperation(account.id, tx, inputAmount - changeAmount, OperationType.Send))
        // only output, consider changeAmount as received
        case (false, true) =>
          List(makeOperation(account.id, tx, outputAmount + changeAmount, OperationType.Receive))
        // both input and output, consider change as deducted from spend
        case (true, true) =>
          List(
            makeOperation(account.id, tx, inputAmount - changeAmount, OperationType.Send),
            makeOperation(account.id, tx, outputAmount, OperationType.Receive)
          )
        case _ => Nil
      }

    }

    operations.update(
      account.id,
      operationToSave
    )

    IO(operationToSave.size)
  }

  private def makeOperation(
      accountId: UUID,
      tx: TransactionView,
      amount: BigInt,
      operationType: OperationType
  ) = {
    Operation(
      Operation
        .uid(
          Operation.AccountId(accountId),
          Operation.TxId(tx.id),
          operationType,
          tx.block.map(_.height)
        ),
      accountId,
      tx.hash,
      tx,
      operationType,
      amount,
      tx.fees,
      tx.block.map(_.time).getOrElse(Instant.now()),
      tx.block.map(_.height)
    )
  }

  def getOperations(
      accountId: UUID,
      limit: Int,
      sort: Option[Sort],
      cursor: Option[String]
  ): IO[GetOperationsResult] = {

    val ops: List[Operation] = operations(accountId)
      //.filter(_.transaction.block.exists(_.height > blockHeight))
      .sortBy(_.transaction.block.get.height)
      .slice(0, limit)

    val total = operations(accountId).count(_.transaction.block.isDefined)

    IO(
      new GetOperationsResult(
        ops,
        total,
        None
      )
    )
  }

  def getOperation(
      accountId: UUID,
      operationId: String
  ): IO[Option[Operation]] = IO.pure(None)

  def getUtxos(
      accountId: UUID,
      limit: Int,
      offset: Int,
      sort: Option[Sort]
  ): IO[GetUtxosResult] = {

    val inputs = transactions(accountId)
      .flatMap(_.inputs)
      .filter(_.belongs)

    val utxos = transactions(accountId)
      .flatMap(tx => tx.outputs.map(o => (tx, o)))
      .filter(o =>
        o._2.belongs && !inputs.exists(i =>
          i.outputHash == o._1.hash && i.address == o._2.address && i.outputIndex == o._2.outputIndex
        )
      )
      .map { case (tx, output) =>
        ConfirmedUtxo(
          tx.block.map(_.height).getOrElse(-1),
          tx.confirmations,
          tx.hash,
          output.outputIndex,
          output.value,
          output.address,
          output.scriptHex,
          output.changeType,
          output.derivation.get,
          tx.block.map(_.time).getOrElse(Instant.now())
        )
      }

    val total = transactions(accountId).size

    IO(
      new GetUtxosResult(
        utxos,
        total,
        false
      )
    )

  }

  def getUnconfirmedUtxos(accountId: UUID): IO[List[Utxo]] = IO.pure(Nil)

  def getBalance(accountId: UUID): IO[CurrentBalance] =
    IO.raiseError(new Exception("Not implements Yet"))

  def getBalanceHistory(
      accountId: UUID,
      start: Option[Instant],
      end: Option[Instant],
      interval: Option[Int] = None
  ): IO[GetBalanceHistoryResult] =
    IO.raiseError(new Exception("Not implements Yet"))
}
