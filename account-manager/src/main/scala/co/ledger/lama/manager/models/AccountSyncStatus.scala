package co.ledger.lama.manager.models

import java.time.Instant
import java.util.UUID

import co.ledger.lama.common.models.{AccountGroup, Coin, CoinFamily, ReportError, Status}
import io.circe.JsonObject


case class AccountSyncStatus(
    id: UUID,
    key: String,
    coinFamily: CoinFamily,
    coin: Coin,
    syncFrequency: Long,
    label: Option[String],
    group: AccountGroup,
    syncId: UUID,
    status: Status,
    cursor: Option[JsonObject],
    error: Option[ReportError],
    updated: Instant
)
