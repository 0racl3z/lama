package co.ledger.lama.bitcoin.interpreter.models

import cats.effect.IO
import co.ledger.lama.common.models.implicits._
import co.ledger.lama.bitcoin.common.models.interpreter.{Operation, OperationType}
import co.ledger.lama.common.logging.DefaultContextLogging
import io.circe.generic.extras.semiauto._
import io.circe.{Decoder, Encoder}
import fs2.Stream

import java.time.Instant
import java.util.UUID

case class OperationToSave(
    uid: Operation.UID,
    accountId: UUID,
    hash: String,
    operationType: OperationType,
    value: BigInt,
    fees: BigInt,
    time: Instant,
    blockHash: Option[String],
    blockHeight: Option[Long]
)

object OperationToSave {
  implicit val encoder: Encoder[OperationToSave] =
    deriveConfiguredEncoder[OperationToSave]
  implicit val decoder: Decoder[OperationToSave] =
    deriveConfiguredDecoder[OperationToSave]
}

case class TransactionAmounts(
    accountId: UUID,
    hash: String,
    blockHash: Option[String],
    blockHeight: Option[Long],
    blockTime: Option[Instant],
    fees: BigInt,
    inputAmount: BigInt,
    outputAmount: BigInt,
    changeAmount: BigInt
) extends DefaultContextLogging {

  def computeOperations: fs2.Stream[IO, OperationToSave] = {
    TransactionType.fromAmounts(inputAmount, outputAmount, changeAmount) match {
      case SendType =>
        Stream.emit(makeOperationToSave(inputAmount - changeAmount, OperationType.Send))
      case ReceiveType =>
        Stream.emit(makeOperationToSave(outputAmount + changeAmount, OperationType.Receive))
      case ChangeOnlyType =>
        Stream.emit(makeOperationToSave(changeAmount, OperationType.Receive))
      case BothType =>
        Stream(
          makeOperationToSave(inputAmount - changeAmount, OperationType.Send),
          makeOperationToSave(outputAmount, OperationType.Receive)
        )
      case NoneType =>
        Stream
          .eval(
            log.error(
              s"Error on tx : $hash, no transaction type found for amounts : input: $inputAmount, output: $outputAmount, change: $changeAmount"
            )
          )
          .flatMap(_ => Stream.empty)
    }
  }

  private def makeOperationToSave(amount: BigInt, operationType: OperationType) = {
    OperationToSave(
      Operation.uid(Operation.AccountId(accountId), Operation.TxId(hash), operationType),
      accountId = accountId,
      hash = hash,
      operationType = operationType,
      value = amount,
      time = blockTime.getOrElse(Instant.now()),
      blockHash = blockHash,
      blockHeight = blockHeight,
      fees = fees
    )
  }
}
