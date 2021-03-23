package co.ledger.lama.bitcoin.common.models.explorer

import java.time.Instant

import co.ledger.lama.bitcoin.common.models.interpreter.{
  BlockView,
  InputView,
  OutputView,
  TransactionView
}
import co.ledger.lama.bitcoin.interpreter.protobuf
import co.ledger.lama.common.models.implicits._
import co.ledger.lama.common.utils.TimestampProtoUtils
import io.circe.{Decoder, Encoder}
import io.circe.syntax._
import io.circe.generic.extras.semiauto.{deriveConfiguredDecoder, deriveConfiguredEncoder}

case class TxHashWithHex(val transactionHash: String, val hex: String) {
 implicit val encoder: Encoder[TxHashWithHex] =
   deriveConfiguredEncoder[TxHashWithHex]

 implicit val decoder: Decoder[TxHashWithHex] =
   deriveConfiguredDecoder[TxHashWithHex]
}

case class ExplorerTransaction (
  val id: String,
  val hash: String,
  val receivedAt: Instant,
  val lockTime: Long,
  val fees: BigInt,
  val inputs: Seq[Input],
  val outputs: Seq[Output],
  val block: Option[Block],
  val confirmations: Int
) {

 implicit val encoder: Encoder[ExplorerTransaction] =
   deriveConfiguredEncoder[ExplorerTransaction]

 implicit val decoder: Decoder[ExplorerTransaction] =
   deriveConfiguredDecoder[ExplorerTransaction]
}

sealed trait TransactionWithHex {
  val id: String
  val rawHex: String
  val hash: String
  val receivedAt: Instant
  val lockTime: Long
  val fees: BigInt
  val inputs: Seq[Input]
  val outputs: Seq[Output]
  val confirmations: Int

  def toTransactionView: TransactionView
}

case class ConfirmedTransaction(
    id: String,
    rawHex: String,
    hash: String,
    receivedAt: Instant,
    lockTime: Long,
    fees: BigInt,
    inputs: Seq[Input],
    outputs: Seq[Output],
    block: Block,
    confirmations: Int
) extends TransactionWithHex {
  def toProto: protobuf.Transaction =
    protobuf.Transaction(
      id,
      rawHex,
      hash,
      Some(TimestampProtoUtils.serialize(receivedAt)),
      lockTime,
      fees.toString,
      inputs.map(_.toProto),
      outputs.map(_.toProto),
      Some(block.toProto),
      confirmations
    )

  def toTransactionView: TransactionView =
    TransactionView(
      id,
      rawHex,
      hash,
      receivedAt,
      lockTime,
      fees,
      inputs.collect { case i: DefaultInput =>
        InputView(
          i.outputHash,
          i.outputIndex,
          i.inputIndex,
          i.value,
          i.address,
          i.scriptSignature,
          i.txinwitness,
          i.sequence,
          None
        )
      },
      outputs.map { o =>
        OutputView(
          o.outputIndex,
          o.value,
          o.address,
          o.scriptHex,
          None,
          None
        )
      },
      Some(
        BlockView(
          block.hash,
          block.height,
          block.time
        )
      ),
      confirmations
    )
}

object ConfirmedTransaction {
  implicit val encoder: Encoder[ConfirmedTransaction] =
    deriveConfiguredEncoder[ConfirmedTransaction]

  implicit val decoder: Decoder[ConfirmedTransaction] =
    deriveConfiguredDecoder[ConfirmedTransaction]

  def fromProto(proto: protobuf.Transaction): ConfirmedTransaction =
    ConfirmedTransaction(
      proto.id,
      proto.rawHex,
      proto.hash,
      proto.receivedAt.map(TimestampProtoUtils.deserialize).getOrElse(Instant.now),
      proto.lockTime,
      BigInt(proto.fees),
      proto.inputs.map(Input.fromProto),
      proto.outputs.map(Output.fromProto),
      Block.fromProto(
        proto.getBlock
      ), // block should never be missing, it's because of protobuf cc generator
      proto.confirmations
    )
}

case class UnconfirmedTransaction(
    id: String,
    rawHex: String,
    hash: String,
    receivedAt: Instant,
    lockTime: Long,
    fees: BigInt,
    inputs: Seq[Input],
    outputs: Seq[Output],
    confirmations: Int
) extends TransactionWithHex {

  def toTransactionView: TransactionView =
    TransactionView(
      id,
      rawHex,
      hash,
      receivedAt,
      lockTime,
      fees,
      inputs.collect { case i: DefaultInput =>
        InputView(
          i.outputHash,
          i.outputIndex,
          i.inputIndex,
          i.value,
          i.address,
          i.scriptSignature,
          i.txinwitness,
          i.sequence,
          None
        )
      },
      outputs.map { o =>
        OutputView(
          o.outputIndex,
          o.value,
          o.address,
          o.scriptHex,
          None,
          None
        )
      },
      None,
      confirmations
    )
}

object UnconfirmedTransaction {
  implicit val encoder: Encoder[UnconfirmedTransaction] =
    deriveConfiguredEncoder[UnconfirmedTransaction]

  implicit val decoder: Decoder[UnconfirmedTransaction] =
    deriveConfiguredDecoder[UnconfirmedTransaction]

  def fromProto(proto: protobuf.Transaction): UnconfirmedTransaction =
    UnconfirmedTransaction(
      proto.id,
      proto.rawHex,
      proto.hash,
      proto.receivedAt.map(TimestampProtoUtils.deserialize).getOrElse(Instant.now),
      proto.lockTime,
      BigInt(proto.fees),
      proto.inputs.map(Input.fromProto),
      proto.outputs.map(Output.fromProto),
      proto.confirmations
    )
}
