package co.ledger.lama.common.utils

import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors

import cats.data.Kleisli
import cats.effect.ExitCase.{Canceled, Completed, Error}
import cats.effect.{Blocker, ContextShift, IO, Resource, Timer}
import cats.implicits._
import co.ledger.lama.common.logging.IOLogging
import co.ledger.lama.common.utils.ResourceUtils.retriableResource
import dev.profunktor.fs2rabbit.config.declaration.DeclarationQueueConfig
import dev.profunktor.fs2rabbit.config.deletion.{DeletionExchangeConfig, DeletionQueueConfig}
import dev.profunktor.fs2rabbit.config.{Fs2RabbitConfig, deletion}
import dev.profunktor.fs2rabbit.effects.MessageEncoder
import dev.profunktor.fs2rabbit.interpreter.RabbitClient
import dev.profunktor.fs2rabbit.model.{AckResult, _}
import fs2.Stream
import io.circe.parser.parse
import io.circe.syntax._
import io.circe.{Decoder, Encoder}

object RabbitUtils extends IOLogging {

  trait AutoAckMessage[A] {
    def unwrap[B](consumer: A => IO[B]): IO[B]
    def ack(): IO[Unit]
    def nack(): IO[Unit]
  }

  object AutoAckMessage {
    def apply[A](message: A, deliveryTag: DeliveryTag)(implicit acker: AckResult => IO[Unit]): AutoAckMessage[A] = {
      new AutoAckMessage[A] {
        override def unwrap[B](consumer: A => IO[B]): IO[B] = consumer(message).guaranteeCase {
          case Completed => ack()
          case _ => nack()
        }

        override def ack(): IO[Unit] = acker(AckResult.Ack(deliveryTag))

        override def nack(): IO[Unit] = acker(AckResult.NAck(deliveryTag))
      }
    }
  }

  def createClient(
      conf: Fs2RabbitConfig
  )(implicit cs: ContextShift[IO], t: Timer[IO]): Resource[IO, RabbitClient[IO]] = {
    for {
      client <- Resource
        .make(IO(Executors.newCachedThreadPool()))(es => IO(es.shutdown()))
        .map(Blocker.liftExecutorService)
        .evalMap(blocker => RabbitClient[IO](conf, blocker))

      _ = log.logger.info("Creating rabbitmq client")

      _ <- retriableResource(
        "Create rabbitmq client",
        client.createConnectionChannel,
        RetryPolicy.exponential()
      )

      _ = log.logger.info("Rabbitmq client created")
    } yield client
  }

  def declareExchanges(
      R: RabbitClient[IO],
      exchanges: List[(ExchangeName, ExchangeType)]
  ): IO[Unit] =
    R.createConnectionChannel
      .use { implicit channel =>
        exchanges
          .map { case (exchangeName, exchangeType) =>
            R.declareExchange(exchangeName, exchangeType)
          }
          .sequence
          .void
      }

  def declareBindings(
      R: RabbitClient[IO],
      bindings: List[(ExchangeName, RoutingKey, QueueName)]
  ): IO[Unit] =
    R.createConnectionChannel.use { implicit channel =>
      bindings
        .map { case (exchangeName, routingKey, queueName) =>
          R.declareQueue(DeclarationQueueConfig.default(queueName)) *>
            R.bindQueue(queueName, exchangeName, routingKey)
        }
        .sequence
        .void
    }

  def deleteBindings(R: RabbitClient[IO], queues: List[QueueName])(implicit
      cs: ContextShift[IO]
  ): IO[Unit] =
    Stream
      .resource(R.createConnectionChannel)
      .flatMap { implicit channel =>
        Stream
          .emits(queues)
          .map { queueName =>
            Stream.eval {
              R.deleteQueue(
                DeletionQueueConfig(
                  queueName,
                  deletion.Used,
                  deletion.NonEmpty
                )
              )
            }
          }
          .parJoinUnbounded
      }
      .compile
      .drain

  def deleteExchanges(R: RabbitClient[IO], exchanges: List[ExchangeName])(implicit
      cs: ContextShift[IO]
  ): IO[Unit] =
    Stream
      .resource(R.createConnectionChannel)
      .flatMap { implicit channel =>
        Stream
          .emits(exchanges)
          .map { exchangeName =>
            Stream.eval {
              R.deleteExchange(
                DeletionExchangeConfig(exchangeName, deletion.Used)
              )
            }
          }
          .parJoinUnbounded
      }
      .compile
      .drain

  def createAutoAckConsumer[A](
      R: RabbitClient[IO],
      queueName: QueueName
  )(implicit d: Decoder[A]): Stream[IO, A] =
    Stream
      .resource(R.createConnectionChannel)
      .evalMap { implicit channel =>
        R.createAutoAckConsumer[String](queueName)
      }
      .flatten
      .evalMap { message =>
        val parsed = parse(message.payload).flatMap(_.as[A])
        IO.fromEither(parsed)
      }

  def consume[A: Decoder](
      R: RabbitClient[IO],
      queueName: QueueName
  )(implicit channel: AMQPChannel): IO[Stream[IO, A]] =
    R.createAutoAckConsumer[String](queueName).map { queue =>
      queue.evalMap(message => IO.fromEither(io.circe.parser.decode(message.payload)))
    }

  def createConsumer[A](R: RabbitClient[IO], queueName: QueueName)(implicit
      d: Decoder[A]
  ): Stream[IO, AutoAckMessage[A]] = {
    Stream
      .resource(R.createConnectionChannel)
      .evalMap { implicit channel =>
        R.createAckerConsumer[String](queueName)
      }
      .flatMap { case (acker, queue) =>
        implicit val implicitAcker: AckResult => IO[Unit] = acker
        queue.evalMap { message =>
          val parsed = parse(message.payload).flatMap(_.as[A])

          IO.fromEither(parsed.map(AutoAckMessage(_, message.deliveryTag))).guaranteeCase {
            case Canceled | Error(_) => acker(AckResult.NAck(message.deliveryTag))
            case Completed => IO.unit
          }
        }
      }
  }

  def createPublisher[A](
      R: RabbitClient[IO],
      exchangeName: ExchangeName,
      routingKey: RoutingKey
  )(implicit e: Encoder[A]): Stream[IO, A => IO[Unit]] = {
    implicit val me: MessageEncoder[IO, A] =
      Kleisli[IO, A, AmqpMessage[Array[Byte]]] { s =>
        AmqpMessage(
          payload = s.asJson.noSpaces.getBytes(StandardCharsets.UTF_8),
          properties = AmqpProperties.empty
        ).pure[IO]
      }

    Stream
      .resource(R.createConnectionChannel)
      .evalMap { implicit channel =>
        R.createPublisher[A](
          exchangeName,
          routingKey
        )
      }
  }

}
