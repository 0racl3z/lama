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
import fs2._

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

    val recAddr = "recipientAddress"
    // Check co.ledger.lama.bitcoin.common.clients.grpc.mocks.KeychainClientMock.derivationsInternal
    val mockKeychainDerivation = List(1, 1)
    val changeAddr             = "changeAddr1"
    val recipients: List[PrepareTxOutput] = List(
      PrepareTxOutput(recAddr, 15000)
    )

    for {
      // save the transactions with the futures utxos
      _ <- Stream
        .emits(transactions)
        .through(interpreterService.saveTransactions(accountId))
        .compile
        .drain

      // compute to flag utxos as belonging
      _ <- interpreterService.compute(
        accountId,
        Btc,
        List(outputAddress1, outputAddress2, outputAddress3)
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

      // Checking that the response has the extra output for the
      // change Address (so recipients.size + 1)
      recipients should have size 1
      response.outputs should have size 2
      response.outputs.map(_.address) should contain(recAddr)
      response.outputs.map(_.address) should contain(changeAddr)

      // Checking the change fields
      response.outputs.filter(_.address == changeAddr).head.change should be(
        Some(mockKeychainDerivation)
      )
      response.outputs.filter(_.address == recAddr).head.change should be(None)
    }
  }

}
