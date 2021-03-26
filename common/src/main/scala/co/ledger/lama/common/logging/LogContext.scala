package co.ledger.lama.common.logging

trait LogContext {
  def asMap(): Map[String, String]
}
