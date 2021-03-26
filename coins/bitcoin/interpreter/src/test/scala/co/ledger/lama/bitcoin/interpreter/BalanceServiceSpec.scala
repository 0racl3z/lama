package co.ledger.lama.bitcoin.interpreter

import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import co.ledger.lama.bitcoin.common.models.interpreter.BalanceHistory
import co.ledger.lama.bitcoin.interpreter.Config.Db
import co.ledger.lama.bitcoin.interpreter.services.BalanceService
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers

class BalanceServiceSpec extends AnyFlatSpecLike with Matchers {

  private val service = new BalanceService(null, Db.BatchConcurrency(5))

  "getBalancesAtInterval" should "get balances at time interval" in {

    val uuid = UUID.randomUUID()
    val time = Instant.now()

    val balances = (1 to 100).map { value =>
      BalanceHistory(
        uuid,
        value,
        Some(value),
        time.plus(value, ChronoUnit.SECONDS)
      )
    }.toList

    val intervalBalances = service.getBalancesAtInterval(uuid, balances, None, 10)

    val expected = Range(0, 101, 10)

    intervalBalances should have size 11
    intervalBalances.head.balance shouldBe 0
    intervalBalances.last.balance shouldBe 100
    intervalBalances.map(_.balance) shouldBe expected

  }

  it should "get the same balance multiple time in case of a gap" in {

    val uuid = UUID.randomUUID()
    val time = Instant.now()

    val balanceBeforeGap = BalanceHistory(uuid, 1, Some(1), time.minus(1, ChronoUnit.SECONDS))
    val balanceAfterGap  = BalanceHistory(uuid, 2, Some(2), time.plus(100, ChronoUnit.SECONDS))

    val balances = List(
      balanceBeforeGap,
      balanceAfterGap
    )

    val intervalBalances = service.getBalancesAtInterval(uuid, balances, None, 4)

    val expected = List(0, 1, 1, 1, 2)

    intervalBalances should have size 5
    intervalBalances.map(_.balance) shouldBe expected

  }

  it should "get the last balance as many times as necessary to fill all interval" in {

    val uuid = UUID.randomUUID()
    val time = Instant.now()

    val firstBalance = BalanceHistory(uuid, 1, Some(1), time.minus(1, ChronoUnit.SECONDS))
    val lastBalance  = BalanceHistory(uuid, 2, Some(2), time.plus(3, ChronoUnit.SECONDS))

    val balances = List(firstBalance, lastBalance)

    val intervalBalances = service.getBalancesAtInterval(
      uuid,
      balances,
      Some(firstBalance),
      4,
      Some(time),
      Some(time.plus(30, ChronoUnit.SECONDS))
    )

    val expected = List(1, 2, 2, 2, 2)

    intervalBalances should have size 5
    intervalBalances.map(_.balance) shouldBe expected

  }

  it should "get 0 balance until first known balance is reached" in {

    val uuid = UUID.randomUUID()
    val time = Instant.now()

    val firstBalance = BalanceHistory(uuid, 1, Some(1), time.plus(29, ChronoUnit.SECONDS))

    val balances = List(firstBalance)

    val intervalBalances = service.getBalancesAtInterval(
      uuid,
      balances,
      None,
      4,
      Some(time),
      Some(time.plus(30, ChronoUnit.SECONDS))
    )

    val expected = List(0, 0, 0, 0, 1)

    intervalBalances should have size 5
    intervalBalances.map(_.balance) shouldBe expected

  }

}
