package co.ledger.lama.bitcoin.api.routes

import cats.Monoid
import cats.data.NonEmptyList
import cats.effect.{ContextShift, IO}
import cats.implicits._
import co.ledger.lama.bitcoin.api.models.{ApiContext, BalancePreset}
import co.ledger.lama.bitcoin.api.models.accountManager._
import co.ledger.lama.bitcoin.api.models.transactor._
import co.ledger.lama.bitcoin.api.{models => apiModels}
import co.ledger.lama.bitcoin.api.utils.RouterUtils._
import co.ledger.lama.bitcoin.common.clients.grpc.{
  InterpreterClient,
  KeychainClient,
  TransactorClient
}
import co.ledger.lama.common.models.implicits.defaultCirceConfig
import co.ledger.lama.bitcoin.common.models.interpreter.ChangeType
import co.ledger.lama.common.clients.grpc.AccountManagerClient
import co.ledger.lama.common.logging.{ContextLogging, LamaLogContext}
import co.ledger.lama.common.utils.UuidUtils
import io.circe.generic.extras.auto._
import org.http4s.{ContextRequest, ContextRoutes, HttpRoutes, Response}
import org.http4s.circe.CirceEntityCodec._
import org.http4s.dsl.Http4sDsl
import java.time.Instant
import java.time.temporal.ChronoUnit

import co.ledger.lama.manager.Exceptions.AccountNotFoundException

object AccountController extends Http4sDsl[IO] with ContextLogging {

  implicit val cs: ContextShift[IO] = IO.contextShift(scala.concurrent.ExecutionContext.global)

  def accountStatusRoutes(
      accountManagerClient: AccountManagerClient,
      interpreterClient: InterpreterClient
  ): HttpRoutes[IO] = HttpRoutes.of[IO] {

    // Get account info
    case GET -> Root / UUIDVar(accountId) =>
      implicit val lc: LamaLogContext = LamaLogContext().withAccountId(accountId)
      accountManagerClient
        .getAccountInfo(accountId)
        .parProduct(interpreterClient.getBalance(accountId))
        .flatMap { case (accountInfo, balance) =>
          Ok(
            AccountWithBalance(
              accountInfo.account.id,
              accountInfo.account.coinFamily,
              accountInfo.account.coin,
              accountInfo.syncFrequency,
              accountInfo.lastSyncEvent,
              balance.balance,
              balance.unconfirmedBalance,
              balance.utxos,
              balance.received,
              balance.sent,
              accountInfo.label
            )
          )
        }
        .handleErrorWith {
          case AccountNotFoundException(_) =>
            log.info("Account not found")
            IO(Response(NotFound))
          case err =>
            log.error("Error while fetching account info", err)
            IO(Response(InternalServerError))
        }

  }

  def accountRoutes(
      keychainClient: KeychainClient,
      accountManagerClient: AccountManagerClient,
      interpreterClient: InterpreterClient,
      transactorClient: TransactorClient
  ): ContextRoutes[ApiContext, IO] = ContextRoutes.of[ApiContext, IO] {
    case ContextRequest(ApiContext(account, followUpId), req) =>
      implicit val lc: LamaLogContext =
        LamaLogContext().withAccount(account).withFollowUpId(followUpId)

      req match {

        // Get account events
        case GET -> Root / UUIDVar(accountId) / "events"
            :? OptionalBoundedLimitQueryParamMatcher(limit)
            +& OptionalOffsetQueryParamMatcher(offset)
            +& OptionalSortQueryParamMatcher(sort) =>
          for {
            boundedLimit <- parseBoundedLimit(limit)
            _            <- log.info("Get Events")
            res <- accountManagerClient
              .getSyncEvents(accountId, boundedLimit.value, offset, sort)
              .flatMap(Ok(_))
          } yield res

        // Update account
        case req @ PUT -> Root / UUIDVar(accountId) =>
          val r = for {
            updateRequest <- req.as[UpdateRequest]

            _ <- log.info(
              s"Updating account with : $updateRequest"
            )

            _ <- updateRequest match {
              case UpdateLabel(label) => accountManagerClient.updateLabel(accountId, label)
              case UpdateSyncFrequency(syncFrequency) =>
                accountManagerClient.updateSyncFrequency(accountId, syncFrequency)
              case UpdateSyncFrequencyAndLabel(syncFrequency, label) =>
                accountManagerClient.updateAccount(accountId, syncFrequency, label)
            }
          } yield ()
          r.flatMap(_ => Ok())

        // List account operations
        case GET -> Root / UUIDVar(accountId) / "operations" :? OptionalCursorQueryParamMatcher(
              cursor
            )
            +& OptionalBoundedLimitQueryParamMatcher(limit)
            +& OptionalSortQueryParamMatcher(sort) =>
          for {
            boundedLimit <- parseBoundedLimit(limit)
            _            <- log.info(s"Fetching operations for account: $accountId")
            res <- interpreterClient
              .getOperations(
                accountId = accountId,
                limit = boundedLimit.value,
                sort = sort,
                cursor = cursor
              )
              .flatMap(Ok(_))
          } yield res

        // Get operation info
        case GET -> Root / UUIDVar(accountId) / "operations" / uid =>
          log.info(s"Fetching operations for account: $accountId") *>
            interpreterClient
              .getOperation(
                accountId,
                operationId = uid
              )
              .flatMap {
                case Some(operation) => Ok(operation)
                case None            => NotFound(s"No operation in account identified by $uid ")
              }

        // List account UTXOs
        case GET -> Root / UUIDVar(accountId) / "utxos" :? OptionalBoundedLimitQueryParamMatcher(
              limit
            )
            +& OptionalOffsetQueryParamMatcher(offset)
            +& OptionalSortQueryParamMatcher(sort) =>
          (for {
            boundedLimit <- parseBoundedLimit(limit)
            _            <- log.info(s"Fetching UTXOs for account: $accountId")
            internalUtxos <- interpreterClient
              .getUtxos(
                accountId = accountId,
                limit = boundedLimit.value,
                offset = offset.getOrElse(0),
                sort = sort
              )
            apiConfirmedUtxos = internalUtxos.utxos.map(apiModels.ConfirmedUtxo.fromCommon)
            response          = apiModels.GetUtxosResult.fromCommon(internalUtxos, apiConfirmedUtxos)
          } yield response).flatMap(Ok(_))

        // Get account balances
        case GET -> Root / UUIDVar(accountId) / "balances" :? OptionalStartInstantQueryParamMatcher(
              start
            )
            +& OptionalEndInstantQueryParamMatcher(end)
            +& OptionalIntervalQueryParamMatcher(interval) =>
          log.info(s"Fetching balances history for account: $accountId") *>
            interpreterClient
              .getBalanceHistory(
                accountId,
                start,
                end,
                interval
              )
              .flatMap(Ok(_))

        // Get account balances with preset
        case GET -> Root / UUIDVar(accountId) / "balances" / BalancePreset(preset) =>
          log.info(s"Fetching balances history for account: $accountId") *>
            interpreterClient
              .getBalanceHistory(
                accountId,
                Some(Instant.now().minus(preset.durationInSeconds, ChronoUnit.SECONDS)),
                Some(Instant.now()),
                Some(preset.interval)
              )
              .flatMap(Ok(_))

        // List observable account addresses
        case GET -> Root / UUIDVar(_) / "addresses" :? OptionalFromIndexQueryParamMatcher(from)
            +& OptionalToIndexQueryParamMatcher(to)
            +& OptionalChangeTypeParamMatcher(change) =>
          for {
            _          <- log.info("Get Observable Addresses")
            keychainId <- UuidUtils.stringToUuidIO(account.identifier)

            response <- keychainClient
              .getAddresses(
                keychainId,
                from.getOrElse(0),
                to.getOrElse(0),
                change
              )
              .flatMap(Ok(_))

          } yield response

        // List fresh account addresses
        case GET -> Root / UUIDVar(_) / "addresses" / "fresh" :? OptionalChangeTypeParamMatcher(
              change
            ) =>
          for {
            _ <- log.info("Get Fresh Addresses")

            keychainId   <- UuidUtils.stringToUuidIO(account.identifier)
            keychainInfo <- keychainClient.getKeychainInfo(keychainId)

            response <- keychainClient
              .getFreshAddresses(
                keychainId,
                change.getOrElse(ChangeType.External),
                keychainInfo.lookaheadSize
              )
              .flatMap(Ok(_))

          } yield response

        // Resync account
        case GET -> Root / UUIDVar(accountId) / "resync"
            :? OptionalWipeQueryParamMatcher(wipe) =>
          for {
            _ <- log.info(s"Fetching account informations for id: $accountId")

            isWipe = wipe.getOrElse(false)

            _ <- log.info(s"Resyncing account $accountId - wipe=$isWipe")

            _ <-
              if (isWipe) {
                for {
                  _          <- log.info("Resetting keychain")
                  keychainId <- UuidUtils.stringToUuidIO(account.identifier)
                  res        <- keychainClient.resetKeychain(keychainId)
                  _          <- log.info("Removing interpreter data")
                  _ <- interpreterClient.removeDataFromCursor(
                    account.id,
                    None,
                    followUpId
                  )
                } yield res
              } else IO.unit

            res <- accountManagerClient.resyncAccount(accountId, isWipe).flatMap(Ok(_))
          } yield res

        // Unregister account
        case DELETE -> Root / UUIDVar(accountId) =>
          for {
            _ <- log.info(s"Fetching account informations for id: $accountId")

            _          <- log.info("Deleting keychain")
            keychainId <- UuidUtils.stringToUuidIO(account.identifier)
            _ <- keychainClient
              .deleteKeychain(keychainId)
              .map(_ => log.info("Keychain deleted"))
              .handleErrorWith(_ =>
                log.info("An error occurred while deleting the keychain, moving on")
              )

            _ <- log.info("Unregistering account")
            _ <- accountManagerClient.unregisterAccount(account.id)
            _ <- accountManagerClient.unregisterAccount(account.id)
            _ <- accountManagerClient.unregisterAccount(account.id)
            _ <- log.info("Account unregistered")

            res <- Ok()

          } yield res

        // Create transaction
        case POST -> Root / UUIDVar(
              accountId
            ) / "transactions" =>
          (for {
            _                           <- log.info(s"Preparing transaction creation")
            apiCreateTransactionRequest <- req.as[CreateTransactionRequest]

            keychainId <- UuidUtils.stringToUuidIO(account.identifier)

            internalResponse <- transactorClient
              .createTransaction(
                accountId,
                keychainId,
                account.coin,
                apiCreateTransactionRequest.coinSelection,
                apiCreateTransactionRequest.outputs.map(_.toCommon),
                apiCreateTransactionRequest.feeLevel,
                apiCreateTransactionRequest.customFeePerKb,
                apiCreateTransactionRequest.maxUtxos
              )

            apiUtxosDerivations = internalResponse.utxos.map(_.derivation.toList)
            apiUtxosPublicKeys <- keychainClient.getAddressesPublicKeys(
              keychainId,
              apiUtxosDerivations
            )
            apiUtxos = internalResponse.utxos
              .zip(apiUtxosPublicKeys)
              .map { case (commonUtxo, pubKey) =>
                apiModels.SpendableTxo.fromCommon(commonUtxo, pubKey)
              }
            response = CreateTransactionResponse.fromCommon(internalResponse, apiUtxos)
          } yield response).flatMap(Ok(_))

        // Send transaction
        case req @ POST -> Root / UUIDVar(accountId) / "transactions" / "send" =>
          for {
            _       <- log.info(s"Broadcasting transaction for account: $accountId")
            request <- req.as[BroadcastTransactionRequest]

            accountInfo <- accountManagerClient.getAccountInfo(accountId)
            keychainId  <- UuidUtils.stringToUuidIO(accountInfo.account.identifier)

            txInfo <- transactorClient
              .broadcastTransaction(
                keychainId,
                accountInfo.account.coin.name,
                request.rawTransaction,
                request.derivations,
                request.signatures
              )
              .flatMap(Ok(_))

            syncResult <- accountManagerClient.resyncAccount(accountId, wipe = false)
            _          <- log.info(s"Sync sent to account manager: $syncResult")

          } yield txInfo

        case req @ POST -> Root / UUIDVar(_) / "recipients" =>
          for {
            addresses <- req.as[NonEmptyList[String]]
            _         <- log.info(s"Validating addresses : ${addresses.toList.mkString(", ")}")
            result <- transactorClient
              .validateAddresses(account.coin, addresses.map(TransactorClient.Address))
              .map(results =>
                results.collectFold {
                  case TransactorClient.Accepted(address) => ValidationResult.valid(address)
                  case TransactorClient.Rejected(address, reason) =>
                    ValidationResult.invalid(address, reason)
                }
              )
              .flatMap(Ok(_))

          } yield result

      }
  }

}

case class ValidationResult(valid: List[String], invalid: Map[String, String])
object ValidationResult {

  def valid(address: TransactorClient.Address): ValidationResult =
    ValidationResult(valid = List(address.value), invalid = Map.empty)
  def invalid(address: TransactorClient.Address, reason: String): ValidationResult =
    ValidationResult(valid = List.empty, invalid = Map(address.value -> reason))

  implicit val monoid: Monoid[ValidationResult] = new Monoid[ValidationResult] {
    override def empty: ValidationResult = ValidationResult(List.empty, Map.empty)

    override def combine(x: ValidationResult, y: ValidationResult): ValidationResult =
      ValidationResult(
        valid = x.valid ::: y.valid,
        invalid = (x.invalid.toList ::: y.invalid.toList).toMap
      )
  }

}
