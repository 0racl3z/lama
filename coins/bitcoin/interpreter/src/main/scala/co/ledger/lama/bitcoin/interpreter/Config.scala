package co.ledger.lama.bitcoin.interpreter

import co.ledger.lama.common.utils.{GrpcServerConfig, PostgresConfig}
import dev.profunktor.fs2rabbit.config.{Fs2RabbitConfig, Fs2RabbitNodeConfig}
import dev.profunktor.fs2rabbit.model.ExchangeName
import pureconfig.generic.semiauto.deriveReader
import cats.implicits._
import co.ledger.lama.bitcoin.interpreter.Config.Db
import pureconfig.ConfigReader
import pureconfig.error.CannotConvert
import pureconfig.module.cats._

case class Config(
    lamaNotificationsExchangeName: ExchangeName,
    rabbit: Fs2RabbitConfig,
    grpcServer: GrpcServerConfig,
    maxConcurrent: Int = 50, // TODO : bench [Runtime.getRuntime.availableProcessors() * x]
    db: Db
)

object Config {

  case class Db(batchConcurrency: Db.BatchConcurrency, postgres: PostgresConfig)

  object Db {

    case class BatchConcurrency(value: Int) extends AnyVal
    object BatchConcurrency {
      implicit val configReader: ConfigReader[BatchConcurrency] =
        ConfigReader.fromCursor[BatchConcurrency] { cur =>
          cur.asInt.flatMap {
            case i if i > 0 => Right(BatchConcurrency(i))
            case i =>
              cur.failed(CannotConvert(i.toString, "BatchConcurrency", s"$i is not positive"))

          }
        }
    }
  }

  implicit val exchangeNameConfigReader: ConfigReader[ExchangeName] =
    ConfigReader.fromString(str => Right(ExchangeName(str)))
  implicit val rabbitNodeConfigReader: ConfigReader[Fs2RabbitNodeConfig] =
    deriveReader[Fs2RabbitNodeConfig]
  implicit val rabbitConfigReader: ConfigReader[Fs2RabbitConfig] = deriveReader[Fs2RabbitConfig]
  implicit val dbConfigReader: ConfigReader[Db]                  = deriveReader[Db]
  implicit val configReader: ConfigReader[Config]                = deriveReader[Config]
}
