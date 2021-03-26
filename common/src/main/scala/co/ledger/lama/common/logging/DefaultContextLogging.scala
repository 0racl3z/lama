package co.ledger.lama.common.logging

trait DefaultContextLogging extends ContextLogging {

  implicit case object DefaultLogContext extends LogContext {
    def asMap(): Map[String, String] = Map.empty
  }

}
