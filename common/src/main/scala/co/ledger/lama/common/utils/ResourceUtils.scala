package co.ledger.lama.common.utils

import cats.effect.{Async, Blocker, ContextShift, IO, Resource, Timer}
import co.ledger.lama.common.logging.DefaultContextLogging
import doobie.ExecutionContexts
import doobie.hikari.HikariTransactor
import fs2.Stream
import io.grpc._
import org.lyranthe.fs2_grpc.java_runtime.implicits._

object ResourceUtils extends DefaultContextLogging {

  def retriableResource[F[_], O](
      label: String,
      resource: Resource[F, O],
      policy: RetryPolicy = RetryPolicy.linear()
  )(implicit
      T: Timer[F],
      F: Async[F]
  ): Resource[F, O] =
    Stream
      .resource(resource)
      .attempts(policy)
      .evalTap {
        case Left(value) =>
          F.delay(log.logger.info(s"$label - resource acquisition failed : ${value.getMessage}"))
        case Right(_) => F.unit
      }
      .collectFirst { case Right(res) =>
        res
      }
      .compile
      .resource
      .lastOrError

  def postgresTransactor(
      conf: PostgresConfig
  )(implicit contextShift: ContextShift[IO], timer: Timer[IO]): Resource[IO, HikariTransactor[IO]] =
    for {
      ce <- ExecutionContexts.fixedThreadPool[IO](conf.poolSize)

      te <- ExecutionContexts.cachedThreadPool[IO]

      _ = log.logger.info("Creating postgres client")

      db <- retriableResource(
        "Create postgres client",
        HikariTransactor.newHikariTransactor[IO](
          conf.driver,                     // driver classname
          conf.url,                        // connect URL
          conf.user,                       // username
          conf.password,                   // password
          ce,                              // await connection here
          Blocker.liftExecutionContext(te) // execute JDBC operations here
        )
      )

      _ = log.logger.info("Postgres client created")
    } yield db

  def grpcServer(
      conf: GrpcServerConfig,
      services: List[ServerServiceDefinition]
  ): Resource[IO, Server] =
    services
      .foldLeft(ServerBuilder.forPort(conf.port)) { case (builder, service) =>
        builder.addService(service)
      }
      .resource[IO]

  def grpcManagedChannel(conf: GrpcClientConfig): Resource[IO, ManagedChannel] =
    if (conf.ssl) {
      ManagedChannelBuilder
        .forAddress(conf.host, conf.port)
        .resource[IO]
    } else {
      ManagedChannelBuilder
        .forAddress(conf.host, conf.port)
        .usePlaintext()
        .resource[IO]
    }
}
