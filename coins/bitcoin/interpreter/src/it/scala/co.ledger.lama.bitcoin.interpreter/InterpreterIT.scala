package co.ledger.lama.bitcoin.interpreter

import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

import cats.data.NonEmptyList
import cats.effect.IO
import cats.implicits._
import co.ledger.lama.bitcoin.common.clients.http.mocks.ExplorerClientMock
import co.ledger.lama.bitcoin.common.models.explorer.UnconfirmedTransaction
import co.ledger.lama.bitcoin.common.models.interpreter._
import co.ledger.lama.bitcoin.interpreter.Config.Db
import co.ledger.lama.bitcoin.interpreter.models.AccountTxView
import co.ledger.lama.common.models.{Account, AccountGroup, Coin, CoinFamily, Sort}
import co.ledger.lama.common.utils.IOAssertion
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers
import fs2.Stream

class InterpreterIT extends AnyFlatSpecLike with Matchers with TestResources {

  val explorer = new ExplorerClientMock()

  val account: Account =
    Account(
      "b723c553-3a9a-4130-8883-ee2f6c2f9202",
      CoinFamily.Bitcoin,
      Coin.Btc,
      AccountGroup("group")
    )
  val accountId: UUID = account.id

  private val outputAddress1 =
    AccountAddress("1DtwACvd338XtHBFYJRVKRLxviD7YtYADa", ChangeType.External, NonEmptyList.of(1, 0))
  private val outputAddress2 =
    AccountAddress("1LK8UbiRwUzC8KFEbMKvgbvriM9zLMce3C", ChangeType.Internal, NonEmptyList.of(0, 0))
  private val inputAddress =
    AccountAddress("1LD1pARePgXXyZA1J3EyvRtB82vxENs5wQ", ChangeType.External, NonEmptyList.of(1, 1))

  private val time: Instant = Instant.parse("2019-04-04T10:03:22Z")

  val block: BlockView = BlockView(
    "00000000000000000008c76a28e115319fb747eb29a7e0794526d0fe47608379",
    570153,
    time
  )

  val outputs = List(
    OutputView(0, 50000, outputAddress1.accountAddress, "script", None, None),
    OutputView(1, 9434, outputAddress2.accountAddress, "script", None, None)
  )
  val inputs = List(
    InputView(
      "0f38e5f1b12078495a9e80c6e0d77af3d674cfe6096bb6e7909993a53b6e8386",
      0,
      0,
      80000,
      inputAddress.accountAddress,
      "script",
      List(),
      4294967295L,
      None
    )
  )
  val insertTx: TransactionView =
    TransactionView(
      "txId",
      "a8a935c6bc2bd8b3a7c20f107a9eb5f10a315ce27de9d72f3f4e27ac9ec1eb1f",
      time,
      0,
      20566,
      inputs,
      outputs,
      Some(block),
      1
    )

  def saveTxs(interpreter: Interpreter, txs: List[TransactionView]): IO[Unit] =
    Stream
      .emits(txs)
      .map { tx => AccountTxView(accountId, tx) }
      .through(interpreter.saveTransactions)
      .compile
      .drain

  "a transaction" should "have a full lifecycle" in IOAssertion {
    setup() *>
      appResources.use { db =>
        val interpreter =
          new Interpreter(_ => IO.unit, _ => explorer, db, 1, Db.BatchConcurrency(1))

        val block2 = BlockView(
          "0000000000000000000cc9cc204cf3b314d106e69afbea68f2ae7f9e5047ba74",
          block.height + 1,
          time
        )
        val block3 = BlockView(
          "0000000000000000000bf68b57eacbff287ceafecb54a30dc3fd19630c9a3883",
          block.height + 2,
          time
        )

        // intentionally disordered
        val blocksToSave = List(block2, block, block3)

        addToExplorer(insertTx)

        for {
          _ <- saveTxs(
            interpreter,
            List(
              insertTx,
              insertTx.copy(hash = "toto", block = Some(block2)),
              insertTx.copy(hash = "tata", block = Some(block3))
            )
          )

          blocks <- interpreter.getLastBlocks(accountId)

          _ <- interpreter.compute(
            account,
            UUID.randomUUID(),
            List(inputAddress, outputAddress2)
          )

          resOpsBeforeDeletion <- interpreter.getOperations(
            accountId,
            20,
            Sort.Ascending,
            None
          )

          GetOperationsResult(
            opsBeforeDeletion,
            opsBeforeDeletionTotal,
            opsBeforeDeletionCursor
          ) =
            resOpsBeforeDeletion

          resUtxoBeforeDeletion <- interpreter.getUtxos(
            accountId,
            20,
            0,
            Sort.Ascending
          )

          GetUtxosResult(utxosBeforeDeletion, utxosBeforeDeletionTotal, utxosBeforeDeletionTrunc) =
            resUtxoBeforeDeletion

          start = time.minusSeconds(86400)
          end   = time.plusSeconds(86400)
          balancesBeforeDeletion <- interpreter.getBalanceHistory(
            accountId,
            Some(start),
            Some(end),
            0
          )

          _ <- interpreter.removeDataFromCursor(accountId, block.height, UUID.randomUUID())

          resOpsAfterDeletion <- interpreter.getOperations(
            accountId,
            20,
            Sort.Ascending,
            None
          )

          GetOperationsResult(opsAfterDeletion, opsAfterDeletionTotal, opsAfterDeletionCursor) =
            resOpsAfterDeletion

          balancesAfterDeletion <- interpreter.getBalanceHistory(
            accountId,
            Some(start),
            Some(end),
            0
          )

          blocksAfterDelete <- interpreter.getLastBlocks(accountId)
        } yield {
          blocks should be(blocksToSave.sortBy(_.height)(Ordering[Long].reverse))

          opsBeforeDeletion should have size 3
          opsBeforeDeletionTotal shouldBe 3
          opsBeforeDeletionCursor shouldBe None

          utxosBeforeDeletion should have size 3
          utxosBeforeDeletionTotal shouldBe 3
          utxosBeforeDeletionTrunc shouldBe false

          balancesBeforeDeletion should have size 3

          opsAfterDeletion shouldBe empty
          opsAfterDeletionTotal shouldBe 0
          opsAfterDeletionCursor shouldBe None

          balancesAfterDeletion shouldBe empty

          blocksAfterDelete shouldBe empty
        }
      }
  }

  "an unconfirmed transaction" should "have a full lifecycle" in IOAssertion {
    setup() *>
      appResources.use { db =>
        val interpreter =
          new Interpreter(_ => IO.unit, _ => explorer, db, 1, Db.BatchConcurrency(1))

        val uTx = TransactionView(
          "txId",
          "a8a935c6bc2bd8b3a7c20f107a9eb5f10a315ce27de9d72f3f4e27ac9ec1eb1f",
          time,
          0,
          20566,
          inputs,
          outputs,
          None,
          1
        )
        val uTx2 = uTx.copy(
          id = "txId2",
          hash = "a8a935c6bc2bd8b3a7c20f107a9eb5f10a315ce27de9d72f3f4e27ac9ec1eb1e"
        )

        addToExplorer(uTx)
        addToExplorer(uTx2)

        for {
          _ <- saveTxs(interpreter, List(uTx, uTx2))

          _ <- interpreter.compute(
            account,
            UUID.randomUUID(),
            List(outputAddress1)
          )
          res <- interpreter.getOperations(accountId, 20, Sort.Descending, None)
          GetOperationsResult(operations, _, _) = res
          currentBalance <- interpreter.getBalance(accountId)
          balanceHistory <- interpreter.getBalanceHistory(accountId, None, None, 0)
        } yield {
          currentBalance.balance should be(0)
          currentBalance.unconfirmedBalance should be(100000)

          operations should have size 2
          operations.head.blockHeight should be(None)

          balanceHistory.last.balance should be(currentBalance.unconfirmedBalance)
        }
      }

  }

  "an unconfirmed transaction" should "be updated if it's been mined" in IOAssertion {
    setup() *>
      appResources.use { db =>
        val interpreter =
          new Interpreter(_ => IO.unit, _ => explorer, db, 1, Db.BatchConcurrency(1))

        val uTx = TransactionView(
          "txId",
          "a8a935c6bc2bd8b3a7c20f107a9eb5f10a315ce27de9d72f3f4e27ac9ec1eb1f",
          time,
          0,
          20566,
          inputs,
          outputs,
          None,
          1
        )
        addToExplorer(uTx)

        val tx = TransactionView(
          "txId",
          "a8a935c6bc2bd8b3a7c20f107a9eb5f10a315ce27de9d72f3f4e27ac9ec1eb1f",
          time,
          0,
          20566,
          inputs,
          outputs,
          Some(block),
          1
        )

        for {
          _ <- saveTxs(interpreter, List(uTx, tx))

          _ <- interpreter.compute(
            account,
            UUID.randomUUID(),
            List(outputAddress1)
          )
          res <- interpreter.getOperations(accountId, 20, Sort.Descending, None)
          GetOperationsResult(operations, _, _) = res
        } yield {
          operations should have size 1
        }
      }

  }

  "an account" should "go through multiple cycles" in IOAssertion {
    setup() *>
      appResources.use { db =>
        val interpreter =
          new Interpreter(_ => IO.unit, _ => explorer, db, 1, Db.BatchConcurrency(1))

        val uTx1 = TransactionView(
          "tx1",
          "tx1",
          time,
          0,
          1000,
          List(
            InputView(".", 0, 0, 101000, inputAddress.accountAddress, "script", List(), 0L, None)
          ),
          List(
            OutputView(0, 100000, outputAddress1.accountAddress, "script", None, None)
          ), // receive
          None,
          1
        )
        addToExplorer(uTx1)

        val uTx2 = uTx1.copy(
          id = "tx2",
          hash = "tx2",
          outputs = List(
            OutputView(0, 5000, outputAddress2.accountAddress, "script", None, None)
          ) // receive
        )
        addToExplorer(uTx2)

        val uTx3 = uTx1.copy(
          id = "tx3",
          hash = "tx3",
          inputs = List(
            InputView(
              "tx1",
              0,
              0,
              100000,
              outputAddress1.accountAddress,
              "script",
              List(),
              0L,
              None
            ) // send utxo from tx1
          ),
          outputs = List(OutputView(0, 99000, inputAddress.accountAddress, "script", None, None))
        )
        addToExplorer(uTx3)

        for {
          _             <- saveTxs(interpreter, List(uTx1))
          _             <- interpreter.compute(account, UUID.randomUUID(), List(outputAddress1))
          firstBalance  <- interpreter.getBalance(accountId)
          firstBalanceH <- interpreter.getBalanceHistory(accountId, None, None, 0)
          r1            <- interpreter.getOperations(accountId, 20, Sort.Descending, None)
          GetOperationsResult(firstOperations, _, _) = r1

          _ <- saveTxs(
            interpreter,
            List(
              uTx1.copy(block = Some(BlockView("block1", 1L, time))), // mine first transaction
              uTx2
            )
          )
          _              <- interpreter.compute(account, UUID.randomUUID(), List(outputAddress1, outputAddress2))
          secondBalance  <- interpreter.getBalance(accountId)
          secondBalanceH <- interpreter.getBalanceHistory(accountId, None, None, 0)
          r2             <- interpreter.getOperations(accountId, 20, Sort.Descending, None)
          GetOperationsResult(secondOperations, _, _) = r2

          _ <- saveTxs(
            interpreter,
            List(
              uTx2.copy(block =
                Some(BlockView("block2", 2L, time.plus(10, ChronoUnit.MINUTES)))
              ), // mine second transaction
              uTx3
            )
          )
          _            <- interpreter.compute(account, UUID.randomUUID(), List(outputAddress1, outputAddress2))
          lastBalance  <- interpreter.getBalance(accountId)
          lastBalanceH <- interpreter.getBalanceHistory(accountId, None, None, 0)
          r3           <- interpreter.getOperations(accountId, 20, Sort.Descending, None)
          GetOperationsResult(lastOperations, _, _) = r3

        } yield {

          firstOperations should have size 1
          firstBalance.balance shouldBe 0
          firstBalance.unconfirmedBalance shouldBe 100000
          // mempool
          firstBalanceH.last.balance shouldBe firstBalance.unconfirmedBalance
          firstBalanceH.last.blockHeight shouldBe None

          val firstBH = BalanceHistory(accountId, firstBalance.unconfirmedBalance, Some(1L), time)
          secondOperations should have size 2
          secondBalance.balance shouldBe 100000
          secondBalance.unconfirmedBalance shouldBe 5000;
          // mempool
          secondBalanceH.last.balance shouldBe secondBalance.unconfirmedBalance + secondBalance.balance
          secondBalanceH.last.blockHeight shouldBe None
          // first block
          secondBalanceH.head.balance shouldBe firstBH.balance
          secondBalanceH.head.blockHeight shouldBe firstBH.blockHeight

          val secondBH = BalanceHistory(
            accountId,
            secondBalance.unconfirmedBalance + secondBalance.balance,
            Some(2L),
            time.plus(10, ChronoUnit.MINUTES)
          )
          lastOperations should have size 3
          lastBalance.balance shouldBe 105000
          lastBalance.unconfirmedBalance shouldBe -100000
          // mempool
          lastBalanceH.last.balance shouldBe lastBalance.unconfirmedBalance + lastBalance.balance
          lastBalanceH.last.blockHeight shouldBe None
          // second block
          lastBalanceH(1).balance shouldBe secondBH.balance
          lastBalanceH(1).blockHeight shouldBe secondBH.blockHeight
          // first block
          lastBalanceH.head.balance shouldBe firstBH.balance
          lastBalanceH.head.blockHeight shouldBe firstBH.blockHeight
        }
      }

  }

  "A transaction rejected by the network" should "not stay in db" in IOAssertion {
    setup() *>
      appResources.use { db =>
        val interpreter =
          new Interpreter(_ => IO.unit, _ => explorer, db, 1, Db.BatchConcurrency(1))

        val uTx1 = AccountTxView(
          accountId,
          TransactionView(
            "utx1",
            "utx1",
            time,
            0,
            1000,
            List(
              InputView(".", 0, 0, 101000, inputAddress.accountAddress, "script", List(), 0L, None)
            ),
            List(
              OutputView(0, 100000, outputAddress1.accountAddress, "script", None, None)
            ),
            None,
            1
          )
        )

        // We add the transaction to the explorer
        addToExplorer(uTx1.tx)

        for {
          _      <- Stream.emit(uTx1).through(interpreter.saveTransactions).compile.drain
          _      <- interpreter.compute(account, UUID.randomUUID(), List(outputAddress1))
          first  <- interpreter.getOperations(accountId, 10, Sort.Ascending, None)
          _      <- Stream.emit(uTx1).through(interpreter.saveTransactions).compile.drain
          _      <- interpreter.compute(account, UUID.randomUUID(), List(outputAddress1))
          second <- interpreter.getOperations(accountId, 10, Sort.Ascending, None)
          // The third time the transaction is not saved again, meaning that it disapeared from the network without being mined.
          // We now remove the transaction from the explorer
          _ = explorer.removeFromBC(uTx1.tx.hash)
          _     <- interpreter.compute(account, UUID.randomUUID(), List(outputAddress1))
          third <- interpreter.getOperations(accountId, 10, Sort.Ascending, None)
        } yield {
          first.total shouldBe 1
          second.total shouldBe 1
          third.total shouldBe 0
        }

      }
  }

  private def addToExplorer(tx: TransactionView) = {
    explorer.addToBC(
      UnconfirmedTransaction(
        tx.hash,
        tx.hash,
        tx.receivedAt,
        tx.lockTime,
        tx.fees,
        Seq(),
        Seq(),
        tx.confirmations
      )
    )
  }
}
