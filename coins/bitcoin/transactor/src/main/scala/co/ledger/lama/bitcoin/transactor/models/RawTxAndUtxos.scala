package co.ledger.lama.bitcoin.transactor.models

import co.ledger.lama.bitcoin.common.models.interpreter.Utxo
import co.ledger.lama.bitcoin.common.models.transactor.RawTransaction

case class RawTxWithChangeFeesAndUtxos(rawTx: RawTransaction, change: Long, totalFees: Long, utxos: List[Utxo])
