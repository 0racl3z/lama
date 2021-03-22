package co.ledger.lama.bitcoin.interpreter

import java.time.Instant
import java.util.UUID

import cats.data.NonEmptyList
import co.ledger.lama.bitcoin.common.models.interpreter._
import co.ledger.lama.bitcoin.interpreter.services.{FlaggingService, OperationService}
import co.ledger.lama.common.models.Sort
import co.ledger.lama.common.utils.IOAssertion
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers

class OperationServiceIT extends AnyFlatSpecLike with Matchers with TestResources {

  val accountId: UUID = UUID.fromString("b723c553-3a9a-4130-8883-ee2f6c2f9202")

  private val outputAddress1 =
    AccountAddress("1DtwACvd338XtHBFYJRVKRLxviD7YtYADa", ChangeType.External, NonEmptyList.of(1, 0))
  private val outputAddress2 =
    AccountAddress("1LK8UbiRwUzC8KFEbMKvgbvriM9zLMce3C", ChangeType.Internal, NonEmptyList.of(0, 0))
  private val inputAddress =
    AccountAddress("1LD1pARePgXXyZA1J3EyvRtB82vxENs5wQ", ChangeType.External, NonEmptyList.of(1, 1))

    val transaction1Hash = "a8a935c6bc2bd8b3a7c20f107a9eb5f10a315ce27de9d72f3f4e27ac9ec1eb1f"
    val transaction1Hex =
      """|010000000186836e3ba5939990e7b66b09e6cf74d6f37ad7e0c6809e5a497820b1f1e5380f00000000
         |6b483045022100f373bb5c9ad2ab5e571571911df1476bf81529efdc6ec984149984ed86d133460220
         |295f37d506dff4568b81c6a1e1621ec34f6356752ce047c80adcc4010d62ed9c0121033d167e565dc1
         |4269dc967c1e116be087853c97bb3ccede19ed6fc799187edf0dffffffff0250c30000000000001976
         |a9148d73eed105d131064de320163eaf3185a51be78488acda240000000000001976a914d3d81e22c6
         |06ee3d4188ab572c13e8f4f76ae0b988ac24b30800""".stripMargin.replaceAll("\n", "")

  val transaction2Hash = "b0c0dc176eaf463a5cecf15f1f55af99a41edfd6e01685068c0db3cc779861c8"
  val transaction2Hex =
    """|0100000001bcea140e91a19ab5eb26e5e46a02fea9fc7aa8519af049ade8d13abaff9d2ab3010000006b
       |483045022100f537b099d8d71f84cba4ae74a1a764d281c3e2530a3156de82bbaf0dbfb70f1302206d1e
       |b869625a726ef2439101ee2956839b6d830399507e86e3fe72fe042ba3ca012103803503d4b69fd86301
       |3edbd4eec9a491e8dd1df571adcac26922209ed85dea66ffffffff0224020000000000001976a91496f9
       |05187d8709f373b73e1f340713bb999de36e88ac05540e00000000001976a91468bbecdb75fbe0da1e18
       |90faec98aee7cdec7e1188ac9cee0900""".stripMargin.replaceAll("\n", "")

  val block1: BlockView = BlockView(
    "00000000000000000008c76a28e115319fb747eb29a7e0794526d0fe47608379",
    570153,
    Instant.parse("2019-04-04T10:03:22Z")
  )

  val block2: BlockView = BlockView(
    "00000000000000000003d16980a4ec530adf4bcefc74ca149a2b1788444e9c3a",
    650909,
    Instant.parse("2020-10-02T11:17:48Z")
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

  val insertTx1: TransactionView =
    TransactionView(
      "txId1",
      transaction1Hex,
      transaction1Hash,
      Instant.parse("2019-04-04T10:03:22Z"),
      0,
      20566,
      inputs,
      outputs,
      Some(block1),
      1
    )

  val insertTx2: TransactionView =
    TransactionView(
      "txId2",
      transaction2Hex,
      transaction2Hash,
      Instant.parse("2019-04-04T10:03:22Z"),
      0,
      30566,
      inputs,
      outputs,
      Some(block2),
      1
    )

  "operation saved in db" should "be fetched" in IOAssertion {
    setup() *>
      appResources.use { db =>
        val operationService = new OperationService(db, conf.maxConcurrent)
        val flaggingService  = new FlaggingService(db)

        for {
          _ <- QueryUtils.saveTx(db, insertTx1, accountId)
          _ <- flaggingService.flagInputsAndOutputs(accountId, List(inputAddress, outputAddress2))
          _ <- operationService
            .compute(accountId)
            .through(operationService.saveOperationSink)
            .compile
            .toList

          res <- operationService.getOperations(
            accountId,
            blockHeight = 0L,
            limit = 20,
            offset = 0,
            sort = Sort.Ascending
          )
          GetOperationsResult(ops, total, trunc) = res
        } yield {
          ops should have size 1
          total shouldBe 1
          trunc shouldBe false

          val op = ops.head
          val tx = op.transaction

          op.accountId shouldBe accountId
          op.hash shouldBe insertTx1.hash
          op.operationType shouldBe OperationType.Send

          tx.fees shouldBe insertTx1.fees

          tx.inputs.find(_.belongs).get.address shouldBe inputAddress.accountAddress
          tx.outputs.find(_.belongs).get.address shouldBe outputAddress2.accountAddress
          tx.outputs.find(_.belongs).get.changeType shouldBe Some(ChangeType.Internal)
        }
      }
  }

  it should "be fetched by uid" in IOAssertion {

    setup() *>
      appResources.use { db =>
        val operationService = new OperationService(db, conf.maxConcurrent)
        val flaggingService  = new FlaggingService(db)

        for {
          _ <- QueryUtils.saveTx(db, insertTx1, accountId)
          _ <- flaggingService.flagInputsAndOutputs(accountId, List(inputAddress, outputAddress2))
          _ <- operationService
            .compute(accountId)
            .through(operationService.saveOperationSink)
            .compile
            .toList

          foundOperation <- operationService.getOperation(
            Operation.AccountId(accountId),
            Operation.uid(
              Operation.AccountId(accountId),
              Operation.TxId(
                insertTx1.hash // because of compute which  put tx.hash in operation.hash instead of txid
              ),
              OperationType.Send
            )
          )

        } yield {

          foundOperation should not be None
          val Some(op) = foundOperation

          val tx = op.transaction

          op.accountId shouldBe accountId
          op.hash shouldBe insertTx1.hash
          op.operationType shouldBe OperationType.Send

          tx.fees shouldBe insertTx1.fees

          tx.inputs.find(_.belongs).get.address shouldBe inputAddress.accountAddress
          tx.outputs.find(_.belongs).get.address shouldBe outputAddress2.accountAddress
          tx.outputs.find(_.belongs).get.changeType shouldBe Some(ChangeType.Internal)
        }
      }

  }

  it should "fetched only ops from a blockHeight cursor" in IOAssertion {
    setup() *>
      appResources.use { db =>
        val operationService = new OperationService(db, conf.maxConcurrent)
        val flaggingService  = new FlaggingService(db)

        for {
          _ <- QueryUtils.saveTx(db, insertTx1, accountId)
          _ <- QueryUtils.saveTx(db, insertTx2, accountId)
          _ <- flaggingService.flagInputsAndOutputs(accountId, List(inputAddress, outputAddress2))
          _ <- operationService
            .compute(accountId)
            .through(operationService.saveOperationSink)
            .compile
            .toList
          res <- operationService.getOperations(
            accountId,
            blockHeight = block2.height,
            limit = 20,
            offset = 0,
            sort = Sort.Ascending
          )
          GetOperationsResult(ops, total, trunc) = res
        } yield {
          ops should have size 1
          total shouldBe 1
          trunc shouldBe false

          val op = ops.head
          val tx = op.transaction

          op.accountId shouldBe accountId
          op.hash shouldBe insertTx2.hash
          op.operationType shouldBe OperationType.Send

          tx.fees shouldBe insertTx2.fees

          tx.inputs.find(_.belongs).get.address shouldBe inputAddress.accountAddress
          tx.outputs.find(_.belongs).get.address shouldBe outputAddress2.accountAddress
          tx.outputs.find(_.belongs).get.changeType shouldBe Some(ChangeType.Internal)
        }
      }
  }

  it should "have made utxos" in IOAssertion {
    setup() *>
      appResources.use { db =>
        val operationService = new OperationService(db, conf.maxConcurrent)
        val flaggingService  = new FlaggingService(db)

        for {
          _ <- QueryUtils.saveTx(db, insertTx1, accountId)
          _ <- flaggingService.flagInputsAndOutputs(accountId, List(inputAddress, outputAddress1))
          _ <- operationService
            .compute(accountId)
            .through(operationService.saveOperationSink)
            .compile
            .toList
          res <- operationService.getUtxos(accountId, Sort.Ascending, 20, 0)
          GetUtxosResult(utxos, total, trunc) = res
        } yield {
          utxos should have size 1
          total shouldBe 1
          trunc shouldBe false

          val utxo = utxos.head

          utxo.address shouldBe outputAddress1.accountAddress
          utxo.changeType shouldBe Some(outputAddress1.changeType)
        }
      }
  }

  "unconfirmed Transactions" should "have made utxos" in IOAssertion {
    setup() *>
      appResources.use { db =>
        val operationService = new OperationService(db, conf.maxConcurrent)

        val unconfirmedTransaction1 = TransactionView(
          "txId1",
          "txHexUnconfirmed1",
          "txHash1",
          Instant.now,
          0L,
          0,
          List(
            InputView(
              "someOtherTransaction",
              0,
              0,
              1000,
              "notMyAddress",
              "script",
              Nil,
              Int.MaxValue,
              None
            )
          ),
          List(
            OutputView( //create UTXO
              0,
              1000,
              "myAddress",
              "script",
              Some(ChangeType.External),
              Some(NonEmptyList(0, List(0)))
            )
          ),
          None,
          0
        )

        val unconfirmedTransaction2 = TransactionView(
          "txId2",
          "txHexUnconfirmed2",
          "txHash2",
          Instant.now,
          0L,
          0,
          List(
            InputView( //using previously made UTXO
              "txHash1",
              0,
              0,
              1000,
              "myAddress",
              "script",
              Nil,
              Int.MaxValue,
              Some(NonEmptyList(0, List(0)))
            )
          ),
          List( //creating 2 new UTXOs
            OutputView(
              0,
              250,
              "myAddress2",
              "script",
              Some(ChangeType.Internal),
              Some(NonEmptyList(1, List(0)))
            ),
            OutputView(
              1,
              250,
              "myAddress3",
              "script",
              Some(ChangeType.External),
              Some(NonEmptyList(0, List(2)))
            ),
            OutputView(
              0,
              800,
              "notMyAddress",
              "script",
              None,
              None
            )
          ),
          None,
          0
        )

        for {
          _     <- QueryUtils.saveTx(db, unconfirmedTransaction1, accountId)
          _     <- QueryUtils.saveTx(db, unconfirmedTransaction2, accountId)
          utxos <- operationService.getUnconfirmedUtxos(accountId)
        } yield {
          utxos should have size 2
          utxos.map(_.value).sum should be(500)
        }
      }
  }

}
