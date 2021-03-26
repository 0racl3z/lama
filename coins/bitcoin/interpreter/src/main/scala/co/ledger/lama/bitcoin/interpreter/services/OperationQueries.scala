package co.ledger.lama.bitcoin.interpreter.services

import cats.data.NonEmptyList
import cats.implicits._
import co.ledger.lama.bitcoin.common.models.interpreter._
import co.ledger.lama.bitcoin.interpreter.models.{OperationToSave, TransactionAmounts}
import co.ledger.lama.bitcoin.interpreter.models.implicits._
import co.ledger.lama.common.logging.DoobieLogHandler
import co.ledger.lama.common.models.{Sort, TxHash}
import doobie._
import doobie.implicits._
import doobie.postgres.implicits._
import fs2.{Chunk, Pipe, Stream}
import java.time.Instant
import java.util.UUID

object OperationQueries extends DoobieLogHandler {

  implicit val txHashRead: Read[TxHash]   = Read[String].map(TxHash.apply)
  implicit val txHashWrite: Write[TxHash] = Write[String].contramap(_.hex)

  case class Tx(
      id: String,
      hash: TxHash,
      receivedAt: Instant,
      lockTime: Long,
      fees: BigInt,
      block: Option[BlockView],
      confirmations: Int
  )

  case class Op(
      uid: Operation.UID,
      accountId: UUID,
      hash: TxHash,
      operationType: OperationType,
      amount: BigInt,
      fees: BigInt,
      time: Instant,
      blockHeight: Option[Long]
  )

  case class OpWithoutDetails(op: Op, tx: Tx)
  case class TransactionDetails(
      txHash: TxHash,
      inputs: List[InputView],
      outputs: List[OutputView]
  )

  def fetchTransactionDetails(
      accountId: UUID,
      sort: Sort,
      txHashes: NonEmptyList[TxHash]
  ): Stream[doobie.ConnectionIO, TransactionDetails] = {
    log.logger.debug(
      s"Fetching inputs and outputs for accountId $accountId and hashes in $txHashes"
    )

    def groupByTxHash[T]: Pipe[ConnectionIO, (TxHash, T), (TxHash, Chunk[T])] =
      _.groupAdjacentBy { case (txHash, _) => txHash }
        .map { case (txHash, chunks) => txHash -> chunks.map(_._2) }

    val inputs  = fetchInputs(accountId, sort, txHashes).stream.through(groupByTxHash)
    val outputs = fetchOutputs(accountId, sort, txHashes).stream.through(groupByTxHash)

    inputs
      .zip(outputs)
      .collect {
        case ((txhash1, i), (txHash2, o)) if txhash1 == txHash2 =>
          TransactionDetails(
            txhash1,
            inputs = i.toList.flatten.sortBy(i => (i.outputHash, i.outputIndex)),
            outputs = o.toList.flatten.sortBy(_.outputIndex)
          )
      }
  }

  def fetchTransactionAmounts(
      accountId: UUID
  ): Stream[ConnectionIO, TransactionAmounts] =
    sql"""SELECT tx.account_id,
                 tx.hash,
                 tx.block_hash,
                 tx.block_height,
                 tx.block_time,
                 tx.fees,
                 COALESCE(tx.input_amount, 0),
                 COALESCE(tx.output_amount, 0),
                 COALESCE(tx.change_amount, 0)
          FROM transaction_amount tx
            LEFT JOIN operation op
              ON op.hash = tx.hash
              AND op.account_id = tx.account_id
          WHERE op.hash IS null
          AND tx.account_id = $accountId
       """
      .query[TransactionAmounts]
      .stream

  def countUTXOs(accountId: UUID): ConnectionIO[Int] =
    sql"""SELECT COUNT(*)
          FROM output o
            LEFT JOIN input i
              ON o.account_id = i.account_id
              AND o.address = i.address
              AND o.output_index = i.output_index
              AND o.hash = i.output_hash
            INNER JOIN transaction tx
              ON o.account_id = tx.account_id
              AND o.hash = tx.hash
          WHERE o.account_id = $accountId
            AND o.derivation IS NOT NULL
            AND i.address IS NULL
            AND tx.block_hash IS NOT NULL
       """
      .query[Int]
      .unique

  def fetchUTXOs(
      accountId: UUID,
      sort: Sort = Sort.Ascending,
      limit: Option[Int] = None,
      offset: Option[Int] = None
  ): Stream[ConnectionIO, Utxo] = {
    val orderF  = Fragment.const(s"ORDER BY tx.block_time $sort, tx.hash $sort")
    val limitF  = limit.map(l => fr"LIMIT $l").getOrElse(Fragment.empty)
    val offsetF = offset.map(o => fr"OFFSET $o").getOrElse(Fragment.empty)

    val query =
      sql"""SELECT tx.hash, o.output_index, o.value, o.address, o.script_hex, o.change_type, o.derivation, tx.block_time
            FROM output o
              LEFT JOIN input i
                ON o.account_id = i.account_id
                AND o.address = i.address
                AND o.output_index = i.output_index
			          AND o.hash = i.output_hash
              INNER JOIN transaction tx
                ON o.account_id = tx.account_id
                AND o.hash = tx.hash
                AND tx.block_hash IS NOT NULL
            WHERE o.account_id = $accountId
              AND o.derivation IS NOT NULL
              AND i.address IS NULL
         """ ++ orderF ++ limitF ++ offsetF
    query.query[Utxo].stream
  }

  def fetchUnconfirmedUTXOs(
      accountId: UUID
  ): Stream[ConnectionIO, Utxo] =
    sql"""SELECT tx.hash, o.output_index, o.value, o.address, o.script_hex, o.change_type, o.derivation, tx.received_at
            FROM output o
              LEFT JOIN input i
                ON o.account_id = i.account_id
                AND o.address = i.address
                AND o.output_index = i.output_index
			          AND o.hash = i.output_hash
              INNER JOIN transaction tx
                ON o.account_id = tx.account_id
                AND o.hash = tx.hash
                AND tx.block_hash IS NULL
            WHERE o.account_id = $accountId
              AND o.derivation IS NOT NULL
              AND i.address IS NULL
         """.query[Utxo].stream

  def saveOperations(operation: Chunk[OperationToSave]): ConnectionIO[Int] = {
    val query =
      """INSERT INTO operation (
         uid, account_id, hash, operation_type, amount, fees, time, block_hash, block_height
       ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
       ON CONFLICT ON CONSTRAINT operation_pkey DO NOTHING
    """
    Update[OperationToSave](query).updateMany(operation)
  }

  def deleteUnconfirmedOperations(accountId: UUID): doobie.ConnectionIO[Int] = {
    sql"""DELETE FROM operation
         WHERE account_id = $accountId
         AND block_height IS NULL
       """.update.run
  }

  private def transactionOrder(sort: Sort) =
    Fragment.const(s"ORDER BY t.block_time $sort, t.hash $sort")
  private def allTxHashes(hashes: NonEmptyList[TxHash]) =
    Fragments.in(fr"t.hash", hashes.map(_.hex))

  private def fetchInputs(
      accountId: UUID,
      sort: Sort,
      txHashes: NonEmptyList[TxHash]
  ) = {

    val belongsToTxs = allTxHashes(txHashes)

    (sql"""
          SELECT t.hash, i.output_hash, i.output_index, i.input_index, i.value, i.address, i.script_signature, i.txinwitness, i.sequence, i.derivation
            FROM transaction t 
            LEFT JOIN input i on i.account_id = t.account_id and i.hash = t.hash
           WHERE t.account_id = $accountId
             AND $belongsToTxs
       """ ++ transactionOrder(sort))
      .query[(TxHash, Option[InputView])]
  }

  private def fetchOutputs(
      accountId: UUID,
      sort: Sort,
      txHashes: NonEmptyList[TxHash]
  ) = {

    val belongsToTxs = allTxHashes(txHashes)

    (
      sql"""
          SELECT t.hash, output.output_index, output.value, output.address, output.script_hex, output.change_type, output.derivation
            FROM transaction t  
            LEFT JOIN output on output.account_id = t.account_id and output.hash = t.hash
           WHERE t.account_id = $accountId
             AND $belongsToTxs
       """ ++ transactionOrder(sort)
    ).query[(TxHash, Option[OutputView])]
  }

  def countOperations(accountId: UUID, blockHeight: Long = 0L): ConnectionIO[Int] =
    sql"""SELECT COUNT(*) FROM operation WHERE account_id = $accountId AND (block_height >= $blockHeight OR block_height IS NULL)"""
      .query[Int]
      .unique

  private val operationWithTx =
    sql"""
         SELECT 
           o.uid, o.account_id, o.hash, o.operation_type, o.amount, o.fees, o.time, o.block_height,
           t.id, t.hash, t.received_at, t.lock_time, t.fees, t.block_hash, t.block_height, t.block_time, t.confirmations
           FROM "transaction" t 
           JOIN "operation" o on t.hash = o.hash and o.account_id = t.account_id 
       """

  def fetchOperations(
      accountId: UUID,
      blockHeight: Long = 0L,
      sort: Sort = Sort.Descending,
      limit: Option[Int] = None,
      offset: Option[Int] = None
  ): Stream[ConnectionIO, OpWithoutDetails] = {
    val limitF  = limit.map(l => fr"LIMIT $l").getOrElse(Fragment.empty)
    val offsetF = offset.map(o => fr"OFFSET $o").getOrElse(Fragment.empty)

    val filter =
      fr"""
             WHERE o.account_id = $accountId
               AND (o.block_height >= $blockHeight
                OR o.block_height IS NULL)
         """ ++ transactionOrder(sort) ++ limitF ++ offsetF

    (operationWithTx ++ filter)
      .query[OpWithoutDetails]
      .stream
  }

  def findOperation(
      accountId: Operation.AccountId,
      operationId: Operation.UID
  ): ConnectionIO[Option[OpWithoutDetails]] = {

    val filter =
      fr"""
           WHERE o.account_id = ${accountId.value}
             AND o.uid = ${operationId.hex}
           LIMIT 1
        """

    (operationWithTx ++ filter)
      .query[OpWithoutDetails]
      .option
  }

  def flagBelongingInputs(
      accountId: UUID,
      addresses: NonEmptyList[AccountAddress]
  ): ConnectionIO[Int] = {
    val queries = addresses.map { addr =>
      sql"""UPDATE input
            SET derivation = ${addr.derivation.toList}
            WHERE account_id = $accountId
            AND address = ${addr.accountAddress}
         """
    }

    queries.traverse(_.update.run).map(_.toList.sum)
  }

  def flagBelongingOutputs(
      accountId: UUID,
      addresses: NonEmptyList[AccountAddress],
      changeType: ChangeType
  ): ConnectionIO[Int] = {
    val queries = addresses.map { addr =>
      sql"""UPDATE output
            SET change_type = $changeType,
                derivation = ${addr.derivation.toList}
            WHERE account_id = $accountId
            AND address = ${addr.accountAddress}
         """
    }

    queries.traverse(_.update.run).map(_.toList.sum)
  }

  def removeFromCursor(accountId: UUID, blockHeight: Long): ConnectionIO[Int] =
    sql"""DELETE from operation
          WHERE account_id = $accountId
          AND (block_height >= $blockHeight
              OR block_height IS NULL)
       """.update.run
}
