package co.ledger.lama.manager

import cats.effect.{ExitCode, IO, IOApp, Resource}
import co.ledger.lama.common.utils.{RabbitUtils, ResourceUtils}
import co.ledger.lama.common.utils.ResourceUtils.{grpcServer, postgresTransactor}
import co.ledger.lama.manager.config.Config
import com.redis.RedisClient
import pureconfig.ConfigSource

object App extends IOApp {

  def run(args: List[String]): IO[ExitCode] = {
    val conf = ConfigSource.default.loadOrThrow[Config]

    val resources = for {
      db <- postgresTransactor(conf.postgres)

      // rabbitmq client
      rabbitClient <- RabbitUtils.createClient(conf.rabbit)

      // redis client
      redisClient <- ResourceUtils.retriableResource(
        Resource.fromAutoCloseable(IO(new RedisClient(conf.redis.host, conf.redis.port)))
      )

      // create the orchestrator
      orchestrator = new CoinOrchestrator(
        conf.orchestrator,
        db,
        rabbitClient,
        redisClient
      )

      // define rpc service definitions
      serviceDefinitions = List(
        new Service(db, conf.orchestrator.coins).definition
      )

      // create the grpc server
      grpcServer <- grpcServer(conf.grpcServer, serviceDefinitions)
    } yield (grpcServer, orchestrator)

    // start the grpc server and run the orchestrator stream
    resources
      .use {
        case (server, orchestrator) =>
          IO(server.start()).flatMap { _ =>
            orchestrator.run().compile.drain
          }
      }
      .as(ExitCode.Success)
  }

}