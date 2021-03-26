package co.ledger.lama.common

import java.util.UUID

import co.ledger.lama.common.models.{AccountGroup, AccountIdentifier, Coin, CoinFamily}
import co.ledger.lama.common.utils.UuidUtils
import org.scalacheck.Gen
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

class UuidUtilsSpec extends AnyFunSuite with Matchers {

  test("uuid to bytes") {
    val uuids = Gen.listOfN(1000, Gen.uuid).sample.get
    uuids.foreach { uuid =>
      val bytes = UuidUtils.uuidToBytes(uuid)
      assert(UuidUtils.bytesToUuid(bytes).contains(uuid))
    }
  }

  test("account identifier to uuid") {
    assert(
      AccountIdentifier("xpub", CoinFamily.Bitcoin, Coin.Btc, AccountGroup("UuidUtilsSpec:23")).id ==
        UUID.fromString("8f90a1b0-1adb-33ff-bcc1-2db04f60e4df")
    )
  }

}
