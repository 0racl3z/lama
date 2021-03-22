package co.ledger.lama.bitcoin.worker

import cats.data.NonEmptyList
import co.ledger.lama.bitcoin.common.clients.http.ExplorerClient.Address
import co.ledger.lama.bitcoin.common.models.explorer.{
  Block,
  ConfirmedTransaction,
  DefaultInput,
  Input,
  Output,
  UnconfirmedTransaction
}

import java.time.Instant
import scala.util.Random

object TransactionFixture {

  def output(address: Address) = Output(
    outputIndex = 0,
    value = 1L,
    address,
    scriptHex = ""
  )

  def input(address: Address): Input = DefaultInput(
    outputHash = "",
    outputIndex = 0,
    inputIndex = 0,
    value = 1L,
    address = address,
    scriptSignature = "",
    txinwitness = List.empty,
    sequence = 0L
  )

  def transfer(fromAddress: Address): UnconfirmedTransaction =
    unconfirmedTransaction(
      inputs = NonEmptyList.one(input(fromAddress)),
      outputs = NonEmptyList.one(output(s"dest-${Random.nextInt(8)})"))
    )

  def receive(toAddress: Address): UnconfirmedTransaction =
    unconfirmedTransaction(
      inputs = NonEmptyList.one(input(s"sender-${Random.nextInt(8)}")),
      outputs = NonEmptyList.one(output(toAddress))
    )

  object confirmed {

    def transfer(fromAddress: Address, inBlock: Block): ConfirmedTransaction =
      confirmedTransaction(
        inputs = NonEmptyList.one(input(fromAddress)),
        outputs = NonEmptyList.one(output(s"dest-${Random.nextInt(8)})")),
        inBlock
      )

    def receive(toAddress: Address, inBlock: Block): ConfirmedTransaction =
      confirmedTransaction(
        inputs = NonEmptyList.one(input(s"sender-${Random.nextInt(8)}")),
        outputs = NonEmptyList.one(output(toAddress)),
        inBlock
      )

  }

  def unconfirmedTransaction(
      inputs: NonEmptyList[Input],
      outputs: NonEmptyList[Output]
  ): UnconfirmedTransaction = UnconfirmedTransaction(
    id = s"id-${Random.nextInt(100)}",
    rawHex = "very long hex",
    hash = s"hash${Random.nextInt(6)}",
    receivedAt = Instant.now(),
    lockTime = 0L,
    fees = 1,
    inputs = inputs.toList,
    outputs = outputs.toList,
    confirmations = 1
  )

  def confirmedTransaction(
      inputs: NonEmptyList[Input],
      outputs: NonEmptyList[Output],
      block: Block
  ): ConfirmedTransaction = ConfirmedTransaction(
    id = s"id-${Random.nextInt(100) + 100}",
    rawHex = "very long hex",
    hash = s"hash${Random.nextInt(6) + 6}",
    receivedAt = Instant.now(),
    lockTime = 0L,
    fees = 1,
    inputs = inputs.toList,
    outputs = outputs.toList,
    confirmations = 1,
    block = block
  )

}
