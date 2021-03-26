package co.ledger.lama.bitcoin.transactor.models

import co.ledger.lama.bitcoin.common.models.BitcoinLikeNetwork
import co.ledger.lama.bitcoin.transactor.models.implicits._
import co.ledger.protobuf.bitcoin.libgrpc
import com.google.protobuf.ByteString

object bitcoinLib {

  case class CreateTransactionRequest(
      lockTime: Long,
      inputs: Seq[Input],
      outputs: Seq[Output],
      network: BitcoinLikeNetwork,
      changeAddress: String,
      feeSatPerKb: Long
  ) {
    def toProto: libgrpc.CreateTransactionRequest =
      libgrpc.CreateTransactionRequest(
        lockTime.toInt, // We use toInt because, even though we have Long (for
        inputs.map(_.toProto),
        outputs.map(_.toProto),
        Some(network.toLibGrpcProto),
        changeAddress,
        feeSatPerKb
      )
  }

  case class NotEnoughUtxo(
      missingAmount: BigInt
  ) {
    def toProto: libgrpc.NotEnoughUtxo =
      libgrpc.NotEnoughUtxo(
        missingAmount.toLong
      )
  }

  object NotEnoughUtxo {
    def fromProto(proto: libgrpc.NotEnoughUtxo): NotEnoughUtxo =
      NotEnoughUtxo(
        BigInt(proto.missingAmount)
      )
  }

  case class RawTransactionResponse(
      hex: String,
      hash: String,
      witnessHash: String,
      changeAmount: Long,
      totalFees: Long,
      notEnoughUtxo: Option[NotEnoughUtxo]
  ) {
    def toProto: libgrpc.RawTransactionResponse =
      libgrpc.RawTransactionResponse(
        hex,
        hash,
        witnessHash,
        changeAmount,
        totalFees,
        notEnoughUtxo.map(_.toProto)
      )
  }

  object RawTransactionResponse {
    def fromProto(proto: libgrpc.RawTransactionResponse): RawTransactionResponse =
      RawTransactionResponse(
        proto.hex,
        proto.hash,
        proto.witnessHash,
        proto.changeAmount,
        proto.totalFees,
        proto.notEnoughUtxo.map(NotEnoughUtxo.fromProto)
      )
  }

  case class SignatureMetadata(
      derSignature: Array[Byte],
      publicKey: String
  ) {
    def toProto: libgrpc.SignatureMetadata =
      libgrpc.SignatureMetadata(
        ByteString.copyFrom(derSignature),
        publicKey = publicKey,
        addrEncoding = libgrpc.AddressEncoding.ADDRESS_ENCODING_P2PKH
      )
  }

  object SignatureMetadata {
    def fromProto(proto: libgrpc.SignatureMetadata): SignatureMetadata =
      SignatureMetadata(
        proto.derSignature.toByteArray,
        proto.publicKey
      )
  }

  case class Input(
      outputHash: String,
      outputIndex: Int,
      script: String,
      value: BigInt
  ) {
    def toProto: libgrpc.Input =
      libgrpc.Input(
        outputHash,
        outputIndex,
        ByteString.copyFromUtf8(script), // ???
        value.toLong
      )
  }

  object Input {
    def fromProto(proto: libgrpc.Input): Input =
      Input(
        proto.outputHash,
        proto.outputIndex,
        proto.script.toString,
        BigInt(proto.value)
      )
  }

  case class Output(
      address: String,
      value: BigInt
  ) {
    def toProto: libgrpc.Output =
      libgrpc.Output(
        address,
        value.toString
      )
  }

  object Output {
    def fromProto(proto: libgrpc.Output): Output =
      Output(
        proto.address,
        BigInt(proto.value)
      )
  }

}
