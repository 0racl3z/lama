package co.ledger.lama.bitcoin.transactor

import java.time.Instant
import java.util.UUID

import cats.data.NonEmptyList
import co.ledger.lama.bitcoin.common.models.interpreter.{
  AccountAddress,
  BlockView,
  ChangeType,
  OutputView,
  TransactionView
}
import co.ledger.lama.bitcoin.common.models.transactor.{
  CoinSelectionStrategy,
  FeeLevel,
  PrepareTxOutput
}
import co.ledger.lama.bitcoin.common.clients.grpc.mocks.{InterpreterClientMock, KeychainClientMock}
import co.ledger.lama.bitcoin.common.clients.http.mocks.ExplorerClientMock
import co.ledger.lama.bitcoin.transactor.services.BitcoinLibClientServiceMock
import co.ledger.lama.common.models.Coin
import co.ledger.lama.common.models.Coin.Btc
import co.ledger.lama.common.utils.IOAssertion
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers

class TransactorIT extends AnyFlatSpecLike with Matchers {

  "Transactor" should "create hex transaction" in IOAssertion {

    val interpreterService = new InterpreterClientMock
    val bitcoinLibService  = new BitcoinLibClientServiceMock
    val keychainService    = new KeychainClientMock
    val explorerService    = new ExplorerClientMock()
    val transactor =
      new Transactor(
        bitcoinLibService,
        _ => explorerService,
        keychainService,
        interpreterService,
        TransactorConfig(200)
      )

    val accountId = UUID.randomUUID()

    val transactionHash = "a8a935c6bc2bd8b3a7c20f107a9eb5f10a315ce27de9d72f3f4e27ac9ec1eb1f"
    val transactionHex =
      """|010000000186836e3ba5939990e7b66b09e6cf74d6f37ad7e0c6809e5a497820b1f1e5380f00000000
         |6b483045022100f373bb5c9ad2ab5e571571911df1476bf81529efdc6ec984149984ed86d133460220
         |295f37d506dff4568b81c6a1e1621ec34f6356752ce047c80adcc4010d62ed9c0121033d167e565dc1
         |4269dc967c1e116be087853c97bb3ccede19ed6fc799187edf0dffffffff0250c30000000000001976
         |a9148d73eed105d131064de320163eaf3185a51be78488acda240000000000001976a914d3d81e22c6
         |06ee3d4188ab572c13e8f4f76ae0b988ac24b30800""".stripMargin.replaceAll("\n", "")

    val outputAddress1 = AccountAddress(
      "1DtwACvd338XtHBFYJRVKRLxviD7YtYADa",
      ChangeType.External,
      NonEmptyList.of(1, 0)
    )
    val outputAddress2 = AccountAddress(
      "1LK8UbiRwUzC8KFEbMKvgbvriM9zLMce3C",
      ChangeType.External,
      NonEmptyList.of(1, 1)
    )
    val outputAddress3 = AccountAddress(
      "1LD1pARePgXXyZA1J3EyvRtB82vxENs5wQ",
      ChangeType.External,
      NonEmptyList.of(1, 2)
    )

    val outputs = List(
      OutputView(0, 10000, outputAddress1.accountAddress, "script", None, None),
      OutputView(1, 5000, outputAddress2.accountAddress, "script", None, None),
      OutputView(2, 5000, outputAddress3.accountAddress, "script", None, None)
    )

    val block = BlockView(
      "blockHash",
      1L,
      Instant.parse("2019-04-04T10:03:22Z")
    )

    // We need to create some utxos
    val transactions = List(
      TransactionView(
        transactionHash,
        transactionHex,
        transactionHash,
        Instant.parse("2019-04-04T10:03:22Z"),
        0,
        20566,
        Nil,
        outputs,
        Some(block),
        1
      )
    )

    val recipients: List[PrepareTxOutput] = List(
      PrepareTxOutput("recipientAddress", 15000)
    )

    for {
      // save the transactions with the futures utxos
      _ <- interpreterService.saveTransactions(
        accountId,
        transactions
      )

      // compute to flag utxos as belonging
      _ <- interpreterService.compute(
        accountId,
        Btc,
        List(outputAddress1, outputAddress2, outputAddress3),
        Some(block.height)
      )

      // create a transaction using prevously saved utxoq
      response <- transactor.createTransaction(
        accountId,
        UUID.randomUUID(),
        recipients,
        Coin.Btc,
        CoinSelectionStrategy.DepthFirst,
        FeeLevel.Normal,
        None,
        200
      )

    } yield {
      response.hex should have size 3
      response.hex should be("hex")
      response.utxos.head.transactionRawHex should be(transactionHex)
    }
  }

}
