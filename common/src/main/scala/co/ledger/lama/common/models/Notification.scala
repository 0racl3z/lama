package co.ledger.lama.common.models

import java.util.UUID

import co.ledger.lama.common.models.implicits._
import io.circe.{Decoder, Encoder, Json}
import io.circe.generic.extras.semiauto._
import io.circe.syntax._

trait Notification {
  val accountId: UUID
  val coinFamily: CoinFamily
  val coin: Coin
  val status: Notification.Status
  val payload: Json
}

case class OperationNotification(
    accountId: UUID,
    coinFamily: CoinFamily,
    coin: Coin,
    operation: Json
) extends Notification {
  val status: Notification.Status = Notification.Operation
  val payload: Json               = Json.obj("operation" -> operation)
}

object OperationNotification {
  implicit val encoder: Encoder[OperationNotification] =
    deriveConfiguredEncoder[OperationNotification]
  implicit val decoder: Decoder[OperationNotification] =
    deriveConfiguredDecoder[OperationNotification]
}

case class OperationsComputedNotification(
    accountId: UUID,
    coinFamily: CoinFamily,
    coin: Coin,
    operationsCount: Int
) extends Notification {
  val status: Notification.Status = Notification.OperationsComputed
  val payload: Json               = Json.obj("operations_count" -> Json.fromInt(operationsCount))
}

object OperationsComputedNotification {
  implicit val encoder: Encoder[OperationsComputedNotification] =
    deriveConfiguredEncoder[OperationsComputedNotification]
  implicit val decoder: Decoder[OperationsComputedNotification] =
    deriveConfiguredDecoder[OperationsComputedNotification]
}

case class BalanceUpdatedNotification(
    accountId: UUID,
    coinFamily: CoinFamily,
    coin: Coin,
    balanceHistory: Json
) extends Notification {
  val status: Notification.Status = Notification.BalanceUpdated
  val payload: Json               = balanceHistory
}

object BalanceUpdatedNotification {
  implicit val encoder: Encoder[BalanceUpdatedNotification] =
    deriveConfiguredEncoder[BalanceUpdatedNotification]
  implicit val decoder: Decoder[BalanceUpdatedNotification] =
    deriveConfiguredDecoder[BalanceUpdatedNotification]
}

object Notification {
  implicit val encoder: Encoder[Notification] = Encoder.instance {
    case x: OperationsComputedNotification => x.asJson
    case x: BalanceUpdatedNotification     => x.asJson
    case x: OperationNotification          => x.asJson
  }
  implicit val decoder: Decoder[Notification] =
    Decoder[OperationsComputedNotification]
      .map[Notification](identity)
      .or(Decoder[BalanceUpdatedNotification].map[Notification](identity))
      .or(Decoder[OperationNotification].map[Notification](identity))

  abstract class Status(val name: String)

  // OperationsComputed event sent when account operations are computed
  // The payload should be the count of operations computed
  case object OperationsComputed extends Status(name = "operations_computed")
  case object BalanceUpdated     extends Status(name = "balance_updated")
  case object Operation          extends Status(name = "operation")

  object Status {
    val all: Map[String, Status] =
      Map(
        OperationsComputed.name -> OperationsComputed,
        BalanceUpdated.name     -> BalanceUpdated,
        Operation.name          -> Operation
      )

    def fromKey(key: String): Option[Status] = all.get(key)

    implicit val encoder: Encoder[Status] = Encoder.encodeString.contramap(_.name)

    implicit val decoder: Decoder[Status] =
      Decoder.decodeString.emap(fromKey(_).toRight("unable to decode status"))
  }
}
