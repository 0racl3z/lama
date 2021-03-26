package co.ledger.lama.bitcoin.api.middlewares

import cats.data.Kleisli
import cats.effect.IO
import co.ledger.lama.common.logging.DefaultContextLogging
import org.http4s._

object LoggingMiddleware extends DefaultContextLogging {
  def loggingMiddleWare(service: HttpRoutes[IO]): HttpRoutes[IO] =
    Kleisli { (req: Request[IO]) =>
      service(req).map {
        case Status.Successful(resp) =>
          resp
        case resp =>
          log.error(s"""
               |A ${resp.status} error occurred: ${resp.body}
               |Request was : $req""".stripMargin)
          resp
      }
    }
}
