package co.ledger.lama.bitcoin.interpreter.services

import cats.effect.{ContextShift, IO}
import co.ledger.lama.bitcoin.common.models.interpreter.BlockView
import co.ledger.lama.bitcoin.interpreter.models.AccountTxView
import co.ledger.lama.common.logging.DefaultContextLogging
import doobie.Transactor
import doobie.implicits._
import fs2._

import java.util.UUID

class TransactionService(db: Transactor[IO], maxConcurrent: Int) extends DefaultContextLogging {

  def saveTransactions(implicit cs: ContextShift[IO]): Pipe[IO, AccountTxView, Int] =
    _.chunkN(100)
      .parEvalMapUnordered(maxConcurrent) { chunk =>
        Stream
          .chunk(chunk)
          .evalMap(a => TransactionQueries.saveTransaction(a.accountId, a.tx))
          .transact(db)
          .compile
          .foldMonoid
          .flatMap { nbSaved =>
            log.info(s"$nbSaved new transactions saved (from chunk size: ${chunk.size})") *>
              IO.pure(nbSaved)
          }
      }

  def removeFromCursor(accountId: UUID, blockHeight: Long): IO[Int] =
    TransactionQueries
      .removeFromCursor(accountId, blockHeight)
      .flatMap(_ =>
        TransactionQueries
          .deleteUnconfirmedTransactions(accountId)
      )
      .transact(db)

  def getLastBlocks(accountId: UUID): Stream[IO, BlockView] =
    TransactionQueries
      .fetchMostRecentBlocks(accountId)
      .transact(db)

}
