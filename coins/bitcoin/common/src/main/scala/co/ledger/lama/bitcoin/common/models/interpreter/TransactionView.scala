package co.ledger.lama.bitcoin.common.models.interpreter

import java.time.Instant

import co.ledger.lama.bitcoin.interpreter.protobuf
import co.ledger.lama.common.models.implicits._
import co.ledger.lama.common.utils.TimestampProtoUtils
import io.circe.{Decoder, Encoder}
import io.circe.generic.extras.semiauto.{deriveConfiguredDecoder, deriveConfiguredEncoder}

case class TransactionView(
    id: String,
    rawHex: String,
    hash: String,
    receivedAt: Instant,
    lockTime: Long,
    fees: BigInt,
    inputs: Seq[InputView],
    outputs: Seq[OutputView],
    block: Option[BlockView],
    confirmations: Int
) {
  def toProto: protobuf.TransactionView =
    protobuf.TransactionView(
      id,
      rawHex,
      hash,
      Some(TimestampProtoUtils.serialize(receivedAt)),
      lockTime,
      fees.toString,
      inputs.map(_.toProto),
      outputs.map(_.toProto),
      block.map(_.toProto),
      confirmations
    )
}

object TransactionView {
  implicit val encoder: Encoder[TransactionView] = deriveConfiguredEncoder[TransactionView]
  implicit val decoder: Decoder[TransactionView] = deriveConfiguredDecoder[TransactionView]

  def fromProto(proto: protobuf.TransactionView): TransactionView =
    TransactionView(
      proto.id,
      proto.rawHex,
      proto.hash,
      proto.receivedAt.map(TimestampProtoUtils.deserialize).getOrElse(Instant.now),
      proto.lockTime,
      BigInt(proto.fees),
      proto.inputs.map(InputView.fromProto),
      proto.outputs.map(OutputView.fromProto),
      proto.block.map(BlockView.fromProto),
      proto.confirmations
    )
}
