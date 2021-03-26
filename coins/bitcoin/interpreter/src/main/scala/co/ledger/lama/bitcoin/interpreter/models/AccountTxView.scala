package co.ledger.lama.bitcoin.interpreter.models

import co.ledger.lama.bitcoin.common.models.interpreter.TransactionView

import java.util.UUID

case class AccountTxView(accountId: UUID, tx: TransactionView)
