package co.ledger.lama.common.models

import cats.Eq

case class HexString(hex: String) extends AnyVal

object HexString {
  implicit val eq: Eq[HexString]             = Eq.by(_.hex.toLowerCase)
  implicit val ordering: Ordering[HexString] = Ordering.by(_.hex.toLowerCase)
}
