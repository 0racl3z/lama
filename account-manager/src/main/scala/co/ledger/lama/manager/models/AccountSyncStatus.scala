package co.ledger.lama.manager.models

import java.time.Instant
import java.util.UUID

import co.ledger.lama.common.models.{Account, AccountInfo, ReportError, Status, SyncEvent}
import io.circe.JsonObject

case class AccountSyncStatus(
    account: Account,
    syncFrequency: Long,
    label: Option[String],
    syncId: UUID,
    status: Status,
    cursor: Option[JsonObject],
    error: Option[ReportError],
    updated: Instant
) {

  val toAccountInfo: AccountInfo =
    AccountInfo(
      account,
      syncFrequency,
      Some(
        SyncEvent(
          account,
          syncId,
          status,
          cursor,
          error,
          updated
        )
      ),
      label
    )

}
