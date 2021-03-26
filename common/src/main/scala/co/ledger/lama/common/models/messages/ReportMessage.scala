package co.ledger.lama.common.models.messages

import cats.Show
import co.ledger.lama.common.models.{AccountIdentifier, ReportableEvent}
import co.ledger.lama.common.models.implicits._
import io.circe.{Decoder, Encoder}
import io.circe.generic.extras.semiauto._

case class ReportMessage[T](account: AccountIdentifier, event: ReportableEvent[T])

object ReportMessage {
  implicit def encoder[T: Encoder]: Encoder[ReportMessage[T]] =
    deriveConfiguredEncoder[ReportMessage[T]]

  implicit def decoder[T: Decoder]: Decoder[ReportMessage[T]] =
    deriveConfiguredDecoder[ReportMessage[T]]

  implicit def showMessage[T: Encoder]: Show[ReportMessage[T]] = Show.show(reportMsg =>
    s"${reportMsg.account}:")
}
