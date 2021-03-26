package co.ledger.lama.bitcoin.common.clients.http

import cats.effect.{ContextShift, IO, Timer}
import co.ledger.lama.bitcoin.common.Exceptions.ExplorerClientException
import co.ledger.lama.bitcoin.common.clients.http.ExplorerClient.Address
import co.ledger.lama.bitcoin.common.config.ExplorerConfig
import co.ledger.lama.bitcoin.common.models.explorer._
import co.ledger.lama.bitcoin.common.models.transactor.FeeInfo
import co.ledger.lama.common.logging.DefaultContextLogging
import co.ledger.lama.common.models.Coin
import co.ledger.lama.common.models.Coin.{Btc, BtcRegtest, BtcTestnet, Ltc}
import co.ledger.lama.common.utils
import co.ledger.lama.common.utils.IOUtils
import fs2.{Chunk, Pull, Stream}
import io.circe.{Decoder, Json}
import org.http4s.circe.CirceEntityDecoder._
import org.http4s.circe.CirceEntityEncoder._
import org.http4s.client.Client
import org.http4s.{EntityDecoder, Method, Request, Uri}

import scala.concurrent.duration._

trait ExplorerClient {

  def getCurrentBlock: IO[Block]

  def getBlock(hash: String): IO[Option[Block]]

  def getBlock(height: Long): IO[Block]

  def getConfirmedTransactions(
      addresses: Seq[Address],
      blockHash: Option[String]
  )(implicit cs: ContextShift[IO], t: Timer[IO]): Stream[IO, ConfirmedTransaction]

  def getUnconfirmedTransactions(
      addresses: Set[Address]
  )(implicit cs: ContextShift[IO], t: Timer[IO]): Stream[IO, UnconfirmedTransaction]

  def getSmartFees: IO[FeeInfo]

  def broadcastTransaction(tx: String): IO[String]
}

object ExplorerClient {
  type Address = String
}

class ExplorerHttpClient(httpClient: Client[IO], conf: ExplorerConfig, coin: Coin)
    extends ExplorerClient
    with DefaultContextLogging {

  private val coinBasePath = coin match {
    case Btc        => "/blockchain/v3/btc"
    case BtcTestnet => "/blockchain/v3/btc_testnet"
    case BtcRegtest => "/blockchain/v3/btc_regtest"
    case Ltc        => "/blockchain/v3/ltc"
  }

  private def callExpect[A](uri: Uri)(implicit d: EntityDecoder[IO, A]): IO[A] =
    log.debug(uri.toString()) *>
      httpClient
        .expect[A](uri)
        .handleErrorWith(e => IO.raiseError(ExplorerClientException(uri, e)))

  private def callExpect[A](req: Request[IO])(implicit c: EntityDecoder[IO, A]): IO[A] =
    log.debug(req.toString()) *>
      httpClient
        .expect[A](req)
        .handleErrorWith(e => IO.raiseError(ExplorerClientException(req.uri, e)))

  private def callExpectWithRetry[A](
      req: Request[IO]
  )(implicit cs: ContextShift[IO], c: EntityDecoder[IO, A], t: Timer[IO]): IO[A] =
    IOUtils
      .retry(
        callExpect(req).timeout(conf.timeout),
        policy = utils.RetryPolicy.exponential(initial = 500.millis, maxElapsedTime = 30.seconds)
      )
      .handleErrorWith { e =>
        val explorerException = ExplorerClientException(req.uri, e)
        log.error("Explorer error", explorerException) *>
          IO.raiseError(explorerException)
      }

  def getCurrentBlock: IO[Block] =
    callExpect[Block](conf.uri.withPath(s"$coinBasePath/blocks/current"))

  def getBlock(hash: String): IO[Option[Block]] =
    callExpect[List[Block]](conf.uri.withPath(s"$coinBasePath/blocks/$hash"))
      .map(_.headOption)

  def getBlock(height: Long): IO[Block] =
    callExpect[Block](conf.uri.withPath(s"$coinBasePath/blocks/$height"))

  def getConfirmedTransactions(
      addresses: Seq[Address],
      blockHash: Option[String]
  )(implicit cs: ContextShift[IO], t: Timer[IO]): Stream[IO, ConfirmedTransaction] =
    Stream
      .emits(addresses)
      .chunkN(conf.addressesSize)
      .map { chunk =>
        fetchPaginatedTransactions(chunk.toList, blockHash).stream
          .flatMap { res =>
            // The explorer v3 returns also unconfirmed txs, so we need to remove it
            val confirmedTxs = res.txs.collect { case confirmedTx: ConfirmedTransaction =>
              confirmedTx
            }
            Stream.emits(confirmedTxs)
          }
      }
      .parJoinUnbounded

  def getUnconfirmedTransactions(
      addresses: Set[Address]
  )(implicit cs: ContextShift[IO], t: Timer[IO]): Stream[IO, UnconfirmedTransaction] = {

    val getPendingTransactionRequest = (as: Chunk[Address]) => {
      val baseUri = conf.uri
        .withPath(s"$coinBasePath/addresses/${as.toList.mkString(",")}/transactions/pending")
        .withQueryParam("no_token", true)
      Request[IO](Method.GET, baseUri)
    }

    val logInfo = (as: Chunk[Address]) => {
      log.info(
        s"Getting pending txs for addresses: ${as.toList.mkString(",")}"
      )
    }

    Stream
      .emits(addresses.toSeq)
      .chunkN(conf.addressesSize)
      .evalTap(logInfo)
      .map(getPendingTransactionRequest)
      .evalTap(r => log.debug(s"$r"))
      .evalMap(request => callExpectWithRetry[List[UnconfirmedTransaction]](request))
      .flatMap(Stream.emits(_))
  }

  def getSmartFees: IO[FeeInfo] = {
    val feeUri = conf.uri.withPath(s"$coinBasePath/fees")

    for {

      json <- callExpect[Json](feeUri)

      feeInfo <- IO.fromOption {
        json.asObject
          .flatMap { o =>
            val sortedFees = o
              .filterKeys(_.toIntOption.isDefined)
              .toList
              .flatMap { case (_, v) =>
                v.asNumber.flatMap(_.toLong)
              }
              .sorted

            sortedFees match {
              case slow :: normal :: fast :: Nil => Some(FeeInfo(slow, normal, fast))
              case _                             => None
            }
          }
      }(
        ExplorerClientException(
          feeUri,
          new Exception(
            s"Explorer.fees did not conform to expected format. payload :\n${json.spaces2SortKeys}"
          )
        )
      )

    } yield feeInfo

  }

  def broadcastTransaction(tx: String): IO[String] =
    callExpect[SendTransactionResult](
      Request[IO](
        Method.POST,
        conf.uri.withPath(s"$coinBasePath/transactions/send")
      ).withEntity(Json.obj("tx" -> Json.fromString(tx)))
    ).map(_.result)

  private def GetOperationsRequest(addresses: Seq[String], blockHash: Option[String]) = {
    val baseUri =
      conf.uri
        .withPath(s"$coinBasePath/addresses/${addresses.mkString(",")}/transactions")
        .withQueryParam("no_token", true)
        .withQueryParam("batch_size", conf.txsBatchSize)

    Request[IO](
      Method.GET,
      blockHash match {
        case Some(value) => baseUri.withQueryParam("block_hash", value)
        case None        => baseUri
      }
    )

  }

  private def fetchPaginatedTransactions(
      addresses: Seq[String],
      blockHash: Option[String]
  )(implicit
      cs: ContextShift[IO],
      t: Timer[IO],
      decoder: Decoder[GetTransactionsResponse]
  ): Pull[IO, GetTransactionsResponse, Unit] =
    Pull
      .eval(
        log.info(
          s"Getting txs with block_hash=$blockHash for addresses: ${addresses.mkString(",")}"
        ) *>
          callExpectWithRetry[GetTransactionsResponse](GetOperationsRequest(addresses, blockHash))
      )
      .flatMap { res =>
        if (res.truncated) {
          // Txs are not sorted per page,
          // so we need get only confirmed txs and
          // get the most recent fetched block hash for the next cursor
          val lastBlockHash =
            res.txs
              .collect { case confirmedTx: ConfirmedTransaction =>
                confirmedTx
              }
              .maxByOption(_.block.time)
              .map(_.block.hash)

          Pull.output(Chunk(res)) >>
            fetchPaginatedTransactions(addresses, lastBlockHash)
        } else {
          Pull.output(Chunk(res))
        }
      }
}
