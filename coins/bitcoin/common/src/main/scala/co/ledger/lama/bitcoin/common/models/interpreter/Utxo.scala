package co.ledger.lama.bitcoin.common.models.interpreter

import java.time.Instant

import cats.data.NonEmptyList
import co.ledger.lama.bitcoin.interpreter.protobuf
import co.ledger.lama.common.utils.TimestampProtoUtils

case class Utxo(
    transactionHash: String,
    outputIndex: Int,
    value: BigInt,
    address: String,
    scriptHex: String,
    changeType: Option[ChangeType],
    derivation: NonEmptyList[Int],
    time: Instant
) {
  def toProto: protobuf.Utxo =
    protobuf.Utxo(
      transactionHash,
      outputIndex,
      value.toString,
      address,
      scriptHex,
      changeType.getOrElse(ChangeType.External).toProto,
      derivation.toList,
      Some(TimestampProtoUtils.serialize(time))
    )
}

object Utxo {
  def fromProto(proto: protobuf.Utxo): Utxo =
    Utxo(
      proto.transactionHash,
      proto.outputIndex,
      BigInt(proto.value),
      proto.address,
      proto.scriptHex,
      Some(ChangeType.fromProto(proto.changeType)),
      NonEmptyList.fromListUnsafe(proto.derivation.toList),
      proto.time.map(TimestampProtoUtils.deserialize).getOrElse(Instant.now())
    )
}
