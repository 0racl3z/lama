package co.ledger.lama.bitcoin.api.models

import co.ledger.lama.bitcoin.common.models.interpreter
import co.ledger.lama.common.models.implicits._
import io.circe.{Decoder, Encoder}
import io.circe.generic.extras.semiauto.{deriveConfiguredDecoder, deriveConfiguredEncoder}

case class GetUtxosResult(
    utxos: List[PickableUtxo],
    total: Int,
    truncated: Boolean
) {}

object GetUtxosResult {
  implicit val getUTXOsResultDecoder: Decoder[GetUtxosResult] =
    deriveConfiguredDecoder[GetUtxosResult]
  implicit val encoder: Encoder[GetUtxosResult] =
    deriveConfiguredEncoder[GetUtxosResult]

  def fromCommon(
      getUtxos: interpreter.GetUtxosResult,
      utxos: List[PickableUtxo],
  ): GetUtxosResult =
    GetUtxosResult(
      utxos,
      getUtxos.total,
      getUtxos.truncated
    )
}
