package co.ledger.lama.bitcoin.common.models.transactor

import co.ledger.lama.common.models.implicits._
import co.ledger.lama.bitcoin.transactor.protobuf
import io.circe.{Decoder, Encoder}
import io.circe.generic.extras.semiauto.{deriveConfiguredDecoder, deriveConfiguredEncoder}

case class PrepareTxOutput(
    address: String,
    value: BigInt,
    change: Option[List[Int]] = None
) {
  def toProto: protobuf.PrepareTxOutput =
    protobuf.PrepareTxOutput(
      address,
      value.toString,
      change.map(protobuf.Derivation(_))
    )
}

object PrepareTxOutput {
  implicit val encoder: Encoder[PrepareTxOutput] = deriveConfiguredEncoder[PrepareTxOutput]
  implicit val decoder: Decoder[PrepareTxOutput] = deriveConfiguredDecoder[PrepareTxOutput]

  def fromProto(proto: protobuf.PrepareTxOutput): PrepareTxOutput =
    PrepareTxOutput(
      proto.address,
      BigInt(proto.value),
      proto.change.map(_.path.toList)
    )
}
