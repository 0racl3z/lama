package co.ledger.lama.bitcoin.transactor.services

import java.time.Instant

import cats.data.NonEmptyList
import co.ledger.lama.bitcoin.common.models.interpreter.{ChangeType, Utxo}
import co.ledger.lama.bitcoin.common.models.transactor.CoinSelectionStrategy
import co.ledger.lama.bitcoin.transactor.services.CoinSelectionService._
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers

class CoinSelectionServiceTest extends AnyFlatSpecLike with Matchers {

  "A depthFirst Strategy" should "return oldest utxos first" in {

    val utxo = Utxo(
      "also long hex",
      "hash",
      0,
      100000,
      "address",
      "script",
      Some(ChangeType.Internal),
      NonEmptyList.of(0, 1),
      "another change pubkey",
      Instant.now
    )

    val utxos = List(
      utxo,
      utxo.copy(value = 10000)
    )

    CoinSelectionService
      .coinSelection(CoinSelectionStrategy.DepthFirst, utxos, 10000, 100, 200)
      .getOrElse(fail("error during depthFirst selection algo test")) should have size 1

    CoinSelectionService
      .coinSelection(CoinSelectionStrategy.DepthFirst, utxos, 100001, 100, 200)
      .getOrElse(fail("error during depthFirst selection algo test")) should have size 2

  }

  it should "be in error if it goes beyond 10 utxos" in {

    val utxo = Utxo(
      "shorter hex maybe",
      "hash",
      0,
      1000,
      "address",
      "script",
      Some(ChangeType.Internal),
      NonEmptyList.of(0, 1),
      "again a change pubkey",
      Instant.now
    )

    val utxos = (1 to 20).toList.map(_ => utxo.copy())

    CoinSelectionService
      .coinSelection(CoinSelectionStrategy.DepthFirst, utxos, 10000, 100, 5) match {
      case Left(e: NotEnoughUtxos) =>
        e.maxAmount should be(5000)
        e.maxUtxos should be(5)
      case _ => fail("NotEnoughUtxo should have been thrown in depthFirst selection algo test")
    }

    CoinSelectionService
      .coinSelection(CoinSelectionStrategy.DepthFirst, utxos, 100000, 100, 200) match {
      case Left(e: NotEnoughFunds) =>
        e.maxAmount should be(utxos.map(_.value).sum)
      case _ => fail("NotEnoughFunds should have been thrown in depthFirst selection algo test")
    }

  }

  "A optimizeSize Strategy" should "get biggest utxos first" in {

    val utxo = Utxo(
      "hex again",
      "hash",
      0,
      1000,
      "address",
      "script",
      Some(ChangeType.Internal),
      NonEmptyList.of(0, 1),
      "pubkey again",
      Instant.now
    )

    val utxos = List(
      utxo.copy(value = 5000),
      utxo.copy(value = 10000),
      utxo.copy(value = 40000),
      utxo.copy(value = 2000),
      utxo.copy(value = 20000)
    )

    val firstSelection = CoinSelectionService
      .coinSelection(CoinSelectionStrategy.OptimizeSize, utxos, 10000, 100, 200)
      .getOrElse(fail("error during optimizeSize selection algo test"))

    firstSelection should have size 1
    firstSelection.head.value shouldBe 40000

    val secondSelection = CoinSelectionService
      .coinSelection(CoinSelectionStrategy.OptimizeSize, utxos, 50000, 100, 200)
      .getOrElse(fail("error during optimizeSize selection algo test"))

    secondSelection should have size 2
    secondSelection.head.value shouldBe 40000
    secondSelection.last.value shouldBe 20000
  }

  "A mergeOutputs Strategy" should "only aggregate small utxos" in {

    val utxo = Utxo(
      "long hex",
      "hash",
      0,
      1000,
      "address",
      "script",
      Some(ChangeType.Internal),
      NonEmptyList.of(0, 1),
      "a change pubkey",
      Instant.now
    )

    val utxos = List(
      utxo.copy(value = 10000),
      utxo.copy(value = 500),
      utxo.copy(value = 100), // should be ignored because it's too small
      utxo.copy(value = 200),
      utxo.copy(value = 20000),
      utxo.copy(value = 40000)
    )

    val firstSelection = CoinSelectionService
      .coinSelection(CoinSelectionStrategy.MergeOutputs, utxos, 10000, 100, 200)
      .getOrElse(fail("error during mergeOutputs selection algo test"))

    firstSelection should have size 2
    firstSelection.head.value shouldBe 500
  }

}
