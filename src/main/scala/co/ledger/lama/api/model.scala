package co.ledger.lama.api

import scala.util.Try

object model {

  object Currency extends Enumeration {
    val BitcoinUnspecified, BitcoinTestnet3, BitcoinMainnet, BitcoinRegtest = Value

    def unapply(arg: String): Option[Value] = {
      Try(withName(arg)).toOption
    }
  }

}
