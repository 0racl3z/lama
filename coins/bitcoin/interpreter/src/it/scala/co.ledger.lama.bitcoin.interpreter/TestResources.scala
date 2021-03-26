package co.ledger.lama.bitcoin.interpreter

import cats.effect.{ContextShift, IO, Resource, Timer}
import co.ledger.lama.common.utils.{DbUtils, ResourceUtils}
import doobie.util.transactor.Transactor
import org.flywaydb.core.Flyway
import pureconfig.ConfigSource

import scala.concurrent.ExecutionContext

trait TestResources {

  implicit val cs: ContextShift[IO] = IO.contextShift(ExecutionContext.global)
  implicit val t: Timer[IO]         = IO.timer(ExecutionContext.global)

  val conf: Config   = ConfigSource.default.loadOrThrow[Config]
  val flyway: Flyway = DbUtils.flyway(conf.db.postgres)

  def setup(): IO[Unit] = IO(flyway.clean()) *> IO(flyway.migrate())

  def appResources: Resource[IO, Transactor[IO]] =
    ResourceUtils.postgresTransactor(conf.db.postgres)

}
