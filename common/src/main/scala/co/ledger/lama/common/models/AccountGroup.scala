package co.ledger.lama.common.models

import co.ledger.lama.common.models.implicits._
import co.ledger.lama.manager.protobuf
import io.circe.generic.extras.semiauto.{deriveConfiguredDecoder, deriveConfiguredEncoder}
import io.circe.{Decoder, Encoder}

case class AccountGroup(name: String) extends AnyVal {
  def toProto: protobuf.GroupLabel = protobuf.GroupLabel(name)
}

object AccountGroup {
  implicit val decoder: Decoder[AccountGroup] =
    deriveConfiguredDecoder[AccountGroup]
  implicit val encoder: Encoder[AccountGroup] =
    deriveConfiguredEncoder[AccountGroup]

  def fromProto(proto: protobuf.GroupLabel): AccountGroup =
    AccountGroup(proto.value)
}
