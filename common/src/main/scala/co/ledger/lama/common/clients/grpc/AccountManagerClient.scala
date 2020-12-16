package co.ledger.lama.common.clients.grpc

import java.util.UUID

import cats.effect.{ContextShift, IO}
import co.ledger.lama.common.models._
import co.ledger.lama.common.utils.UuidUtils
import co.ledger.lama.manager.protobuf
import io.circe.JsonObject
import io.grpc.{ManagedChannel, Metadata}

trait AccountManagerClient {
  def registerAccount(
      keychainId: UUID,
      coinFamily: CoinFamily,
      coin: Coin,
      syncFrequency: Option[Long],
      label: Option[String]
  ): IO[AccountRegistered]

  def updateSyncFrequency(accountId: UUID, frequency: Long): IO[Unit]
  def updateLabel(accountId: UUID, label: String): IO[Unit]
  def updateAccount(accountId: UUID, frequency: Long, label: String): IO[Unit]

  def unregisterAccount(accountId: UUID): IO[AccountUnregistered]
  def getAccountInfo(accountId: UUID): IO[AccountInfo]
  def getAccounts(limit: Option[Int], offset: Option[Int]): IO[AccountsResult]
  def getSyncEvents(
      accountId: UUID,
      limit: Option[Int],
      offset: Option[Int],
      sort: Option[Sort]
  ): IO[SyncEventsResult[JsonObject]]
}

class AccountManagerGrpcClient(
    val managedChannel: ManagedChannel
)(implicit val cs: ContextShift[IO])
    extends AccountManagerClient {

  val client: protobuf.AccountManagerServiceFs2Grpc[IO, Metadata] =
    GrpcClient.resolveClient(protobuf.AccountManagerServiceFs2Grpc.stub[IO], managedChannel)

  def registerAccount(
      keychainId: UUID,
      coinFamily: CoinFamily,
      coin: Coin,
      syncFrequency: Option[Long],
      label: Option[String]
  ): IO[AccountRegistered] =
    client
      .registerAccount(
        protobuf.RegisterAccountRequest(
          keychainId.toString,
          coinFamily.toProto,
          coin.toProto,
          syncFrequency.getOrElse(0L), // if 0, will use default conf in account manager
          label.map(protobuf.AccountLabel(_))
        ),
        new Metadata
      )
      .map(AccountRegistered.fromProto)

  private def update(accountId: UUID, field: protobuf.UpdateAccountRequest.Field) =
    client
      .updateAccount(
        protobuf.UpdateAccountRequest(
          UuidUtils.uuidToBytes(accountId),
          field
        ),
        new Metadata
      )
      .void

  def updateSyncFrequency(accountId: UUID, frequency: Long): IO[Unit] =
    update(accountId, protobuf.UpdateAccountRequest.Field.SyncFrequency(frequency))

  def updateLabel(accountId: UUID, label: String): IO[Unit] =
    update(accountId, protobuf.UpdateAccountRequest.Field.Label(label))

  def updateAccount(accountId: UUID, frequency: Long, label: String): IO[Unit] =
    update(
      accountId,
      protobuf.UpdateAccountRequest.Field.Info(protobuf.UpdateAccountRequest.Info(frequency, label))
    )

  def unregisterAccount(accountId: UUID): IO[AccountUnregistered] =
    client
      .unregisterAccount(
        protobuf.UnregisterAccountRequest(
          UuidUtils.uuidToBytes(accountId)
        ),
        new Metadata
      )
      .map(AccountUnregistered.fromProto)

  def getAccountInfo(accountId: UUID): IO[AccountInfo] =
    client
      .getAccountInfo(
        protobuf.AccountInfoRequest(
          UuidUtils.uuidToBytes(accountId)
        ),
        new Metadata
      )
      .map(AccountInfo.fromProto) // TODO: type T

  def getAccounts(
      limit: Option[Int] = None,
      offset: Option[Int] = None
  ): IO[AccountsResult] =
    client
      .getAccounts(
        protobuf.GetAccountsRequest(
          limit.getOrElse(0), // if 0, accountManager will default on correct value
          offset.getOrElse(0)
        ),
        new Metadata
      )
      .map(AccountsResult.fromProto)

  def getSyncEvents(
      accountId: UUID,
      limit: Option[Int],
      offset: Option[Int],
      sort: Option[Sort]
  ): IO[SyncEventsResult[JsonObject]] =
    client
      .getSyncEvents(
        protobuf.GetSyncEventsRequest(
          accountId = UuidUtils.uuidToBytes(accountId),
          limit = limit.getOrElse(20),
          offset = offset.getOrElse(0),
          sort = sort.getOrElse(Sort.Descending) match { //TODO: do better
            case Sort.Ascending  => protobuf.SortingOrder.ASC
            case Sort.Descending => protobuf.SortingOrder.DESC
          }
        ),
        new Metadata
      )
      .map(SyncEventsResult.fromProto[JsonObject])
}
