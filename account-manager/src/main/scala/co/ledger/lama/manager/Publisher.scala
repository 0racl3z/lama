package co.ledger.lama.manager

import java.util.UUID

import cats.effect.IO
import co.ledger.lama.common.logging.DefaultContextLogging
import co.ledger.lama.common.models.messages.{WithBusinessId, WorkerMessage}
import co.ledger.lama.common.utils.RabbitUtils
import co.ledger.lama.manager.Exceptions.RedisUnexpectedException
import com.redis.RedisClient
import com.redis.serialization.{Format, Parse}
import com.redis.serialization.Parse.Implicits._
import dev.profunktor.fs2rabbit.interpreter.RabbitClient
import dev.profunktor.fs2rabbit.model.{ExchangeName, RoutingKey}
import fs2.Stream
import io.circe.{Decoder, Encoder, JsonObject}
import io.circe.parser.decode
import io.circe.syntax._

import scala.annotation.nowarn

/** Publisher publishing messages sequentially.
  * Redis is used as a FIFO queue to guarantee the sequence.
  */
trait Publisher[K, V <: WithBusinessId[K]] {
  import Publisher._

  // Max concurrent ongoing messages.
  val maxOnGoingMessages: Int = 1

  // Redis client.
  def redis: RedisClient

  // The inner publish function.
  def publish(message: V): IO[Unit]

  // Implicits for serializing data as json and storing it as binary in redis.
  implicit val dec: Decoder[V]
  implicit val enc: Encoder[V]

  implicit val parse: Parse[V] =
    Parse { bytes =>
      decode[V](new String(bytes)) match {
        case Right(v) => v
        case Left(e)  => throw e
      }
    }

  @nowarn
  implicit val fmt: Format =
    Format { case v: V =>
      v.asJson.noSpaces.getBytes()
    }

  // If the counter of ongoing messages for the key has reached max ongoing messages, add the event to the pending list.
  // Otherwise, publish and increment the counter of ongoing messages.
  def enqueue(e: V): IO[Unit] =
    hasMaxOnGoingMessages(e.businessId).flatMap {
      case true =>
        // enqueue pending messages in redis
        rpushPendingMessages(e)
      case false =>
        // publish and increment the counter of ongoing messages
        publish(e)
          .flatMap(_ => incrOnGoingMessages(e.businessId))
    }.void

  // Remove the top pending event of a key and take the next pending event.
  // If next pending event exists, publish it.
  def dequeue(key: K): IO[Unit] =
    for {
      _         <- decrOnGoingMessages(key)
      nextEvent <- lpopPendingMessages(key)
      result <- nextEvent match {
        case Some(next) =>
          publish(next)
        case None => IO.unit
      }
    } yield result

  // Count the number of ongoing messages
  private def countOnGoingMessages(key: K): IO[Long] =
    IO(redis.get[Long](onGoingMessagesCounterKey(key)).getOrElse(0))

  // Check if the counter of ongoing messages has reached the max.
  private def hasMaxOnGoingMessages(key: K): IO[Boolean] =
    countOnGoingMessages(key).map(_ >= maxOnGoingMessages)

  // https://redis.io/commands/incr
  // Increment the counter of ongoing messages for a key and return the value after.
  private def incrOnGoingMessages(key: K): IO[Long] =
    IO.fromOption(redis.incr(onGoingMessagesCounterKey(key)))(RedisUnexpectedException)

  // https://redis.io/commands/decr
  // If the counter is above 0,
  // decrement the counter of ongoing messages for a key and return the value after.
  private def decrOnGoingMessages(key: K): IO[Long] =
    countOnGoingMessages(key).flatMap {
      case 0 => IO.pure(0)
      case _ => IO.fromOption(redis.decr(onGoingMessagesCounterKey(key)))(RedisUnexpectedException)
    }

  // https://redis.io/commands/rpush
  // Add an event at the last and return the length after.
  private def rpushPendingMessages(event: V): IO[Long] =
    IO.fromOption(redis.rpush(pendingMessagesKey(event.businessId), event))(
      RedisUnexpectedException
    )

  // https://redis.io/commands/lpop
  // Remove the first from a key and if exists, return the next one.
  private def lpopPendingMessages(key: K): IO[Option[V]] =
    IO(redis.lpop[V](pendingMessagesKey(key)))

}

object Publisher {
  // Stored keys.
  def onGoingMessagesCounterKey[K](key: K): String = s"on_going_messages_counter_$key"
  def pendingMessagesKey[K](key: K): String        = s"pending_messages_$key"
}

class WorkerMessagePublisher(
    val redis: RedisClient,
    rabbit: RabbitClient[IO],
    exchangeName: ExchangeName,
    routingKey: RoutingKey
)(implicit val enc: Encoder[WorkerMessage[JsonObject]], val dec: Decoder[WorkerMessage[JsonObject]])
    extends Publisher[UUID, WorkerMessage[JsonObject]]
    with DefaultContextLogging {

  def publish(message: WorkerMessage[JsonObject]): IO[Unit] =
    publisher
      .evalMap(p =>
        p(message) *> log.info(s"Published message to worker: ${message.asJson.toString}")
      )
      .compile
      .drain

  private val publisher: Stream[IO, WorkerMessage[JsonObject] => IO[Unit]] =
    RabbitUtils.createPublisher[WorkerMessage[JsonObject]](
      rabbit,
      exchangeName,
      routingKey
    )

}
