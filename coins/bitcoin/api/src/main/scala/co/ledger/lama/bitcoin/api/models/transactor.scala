package co.ledger.lama.bitcoin.api.models

import co.ledger.lama.bitcoin.common.models.transactor.{
  CoinSelectionStrategy,
  FeeLevel,
  PrepareTxOutput,
  RawTransaction
}
import co.ledger.lama.common.models.implicits._
import io.circe.{Decoder, Encoder}
import io.circe.generic.extras.semiauto._
import co.ledger.lama.bitcoin.common.models

object transactor {

  case class CreateTransactionRequest(
      coinSelection: CoinSelectionStrategy,
      outputs: List[PrepareTxOutput],
      feeLevel: FeeLevel,
      customFeePerKb: Option[Long],
      maxUtxos: Option[Int]
  )

  object CreateTransactionRequest {
    implicit val encoder: Encoder[CreateTransactionRequest] =
      deriveConfiguredEncoder[CreateTransactionRequest]
    implicit val decoder: Decoder[CreateTransactionRequest] =
      deriveConfiguredDecoder[CreateTransactionRequest]
  }

  case class CreateTransactionResponse(
      hex: String,
      hash: String,
      witnessHash: String,
      utxos: List[SpendableTxo],
      fee: Long,
      feePerKb: Long
  )

  object CreateTransactionResponse {
    implicit val encoder: Encoder[CreateTransactionResponse] =
      deriveConfiguredEncoder[CreateTransactionResponse]
    implicit val decoder: Decoder[CreateTransactionResponse] =
      deriveConfiguredDecoder[CreateTransactionResponse]

    def fromCommon(
        resp: models.transactor.CreateTransactionResponse,
        utxosList: List[SpendableTxo]
    ): CreateTransactionResponse =
      CreateTransactionResponse(
        resp.hex,
        resp.hash,
        resp.witnessHash,
        utxosList,
        resp.fee,
        resp.feePerKb
      )

  }

  case class BroadcastTransactionRequest(
      rawTransaction: RawTransaction,
      derivations: List[List[Int]],
      signatures: List[String]
  )

  object BroadcastTransactionRequest {
    implicit val encoder: Encoder[BroadcastTransactionRequest] =
      deriveConfiguredEncoder[BroadcastTransactionRequest]
    implicit val decoder: Decoder[BroadcastTransactionRequest] =
      deriveConfiguredDecoder[BroadcastTransactionRequest]
  }

  case class GenerateSignaturesRequest(
      rawTransaction: RawTransaction,
      utxos: List[SpendableTxo],
      privKey: String
  )

  object GenerateSignaturesRequest {
    implicit val encoder: Encoder[GenerateSignaturesRequest] =
      deriveConfiguredEncoder[GenerateSignaturesRequest]
    implicit val decoder: Decoder[GenerateSignaturesRequest] =
      deriveConfiguredDecoder[GenerateSignaturesRequest]
  }

}
