package co.ledger.lama.manager.utils

import cats.effect.{IO, Resource, Timer}
import co.ledger.lama.common.logging.DefaultContextLogging
import co.ledger.lama.common.utils.ResourceUtils
import co.ledger.lama.manager.config.RedisConfig
import com.redis.RedisClient
import javax.net.ssl.SSLContext

import scala.concurrent.duration._

object RedisUtils extends DefaultContextLogging {

  def createClient(conf: RedisConfig)(implicit t: Timer[IO]): Resource[IO, RedisClient] =
    ResourceUtils.retriableResource(
      "Create redis client",
      Resource.fromAutoCloseable(
        for {
          _ <- log.info("Creating redis client")
          client <- IO.delay {
            new RedisClient(
              host = conf.host,
              port = conf.port,
              database = conf.db,
              secret = if (conf.password.nonEmpty) Some(conf.password) else None,
              timeout = 5.seconds.toMillis.toInt,
              sslContext = if (conf.ssl) Some(SSLContext.getDefault) else None
            )
          }

          _ <- {
            if (client.ping.isDefined) log.info("Redis client created")
            else IO.raiseError(new Exception("Failed to ping redis"))
          }

        } yield client
      )
    )

}
