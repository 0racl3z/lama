package co.ledger.lama.bitcoin.worker.services

import cats.effect.{ContextShift, IO, Timer}
import co.ledger.lama.bitcoin.common.clients.grpc.InterpreterClient
import co.ledger.lama.bitcoin.common.clients.http.ExplorerClient
import co.ledger.lama.bitcoin.common.models.explorer.{
  ConfirmedTransaction,
  DefaultInput,
  Transaction,
  UnconfirmedTransaction
}
import co.ledger.lama.bitcoin.common.models.interpreter.AccountAddress
import co.ledger.lama.bitcoin.worker.services.Keychain.KeychainId
import co.ledger.lama.common.models.Coin
import fs2.{Pipe, Stream}

import java.util.UUID

trait Bookkeeper[F[_]] {
  def record[Tx <: Transaction: Bookkeeper.Recordable](
      coin: Coin,
      accountId: UUID,
      keychainId: UUID,
      blockHash: Option[Bookkeeper.BlockHash]
  ): F[List[AccountAddress]]
}

object Bookkeeper {
  type AccountId = UUID
  type Address   = String
  type BlockHash = String

  def apply(
      keychain: Keychain,
      explorerClient: Coin => ExplorerClient,
      interpreterClient: InterpreterClient
  )(implicit cs: ContextShift[IO]): Bookkeeper[IO] = new Bookkeeper[IO] {
    override def record[Tx <: Transaction: Recordable](
        coin: Coin,
        accountId: AccountId,
        keychainId: AccountId,
        blockHash: Option[BlockHash]
    ): IO[List[AccountAddress]] =
      keychain
        .addresses(keychainId)
        .flatMap { addresses =>
          Stream
            .emit(addresses)
            .through(Bookkeeper.fetchTransactionRecords(explorerClient(coin), blockHash))
            .through(Bookkeeper.saveTransactionRecords(interpreterClient, accountId))
            .foldMonoid
            .through(Bookkeeper.markAddresses(keychain, keychainId))
        }
        .takeWhile(_.nonEmpty)
        .foldMonoid
        .compile
        .toList
        .map(_.flatten)
  }

  case class TransactionRecord[Tx <: Transaction: Recordable](
      tx: Tx,
      usedAddresses: List[AccountAddress]
  )

  def fetchTransactionRecords[Tx <: Transaction](
      explorer: ExplorerClient,
      blockHash: Option[BlockHash]
  )(implicit
      cs: ContextShift[IO],
      recordable: Recordable[Tx]
  ): Pipe[IO, List[AccountAddress], TransactionRecord[Tx]] =
    _.prefetch
      .flatMap { addresses =>
        recordable
          .fetch(explorer)(addresses.map(_.accountAddress).toSet, blockHash)
          .map(tx => TransactionRecord(tx, addressesUsed(addresses)(tx)))
      }

  def saveTransactionRecords[Tx <: Transaction: Recordable](
      interpreter: InterpreterClient,
      accountId: AccountId
  )(implicit recordable: Recordable[Tx]): Pipe[IO, TransactionRecord[Tx], List[AccountAddress]] =
    _.chunks.flatMap { chunk =>
      Stream
        .chunk(chunk.map(_.tx))
        .through(recordable.save(interpreter)(accountId))
        .as(chunk.map(_.usedAddresses).toList.flatten)
    }

  def addressUsedBy(tx: Transaction)(accountAddress: AccountAddress): Boolean = {
    tx.inputs
      .collect { case i: DefaultInput => i.address }
      .contains(accountAddress.accountAddress) ||
    tx.outputs.map(_.address).contains(accountAddress.accountAddress)
  }

  def addressesUsed(
      accountAddresses: List[AccountAddress]
  )(tx: Transaction): List[AccountAddress] =
    accountAddresses.filter(addressUsedBy(tx)).distinct

  def markAddresses[Tx <: Transaction](
      keychain: Keychain,
      keychainId: KeychainId
  ): Pipe[IO, List[AccountAddress], List[AccountAddress]] =
    _.evalTap { addresses =>
      keychain.markAsUsed(keychainId, addresses.distinct.map(_.accountAddress).toSet)
    }

  trait Recordable[Tx <: Transaction] {
    def fetch(
        explorer: ExplorerClient
    )(addresses: Set[Address], block: Option[BlockHash]): Stream[IO, Tx]

    def save(interpreter: InterpreterClient)(accountId: AccountId): Pipe[IO, Tx, Unit] =
      _.map(_.toTransactionView).through(interpreter.saveTransactions(accountId))
  }

  implicit def confirmed(implicit
      cs: ContextShift[IO],
      t: Timer[IO]
  ): Recordable[ConfirmedTransaction] =
    new Recordable[ConfirmedTransaction] {
      override def fetch(
          explorer: ExplorerClient
      )(addresses: Set[Address], block: Option[BlockHash]): Stream[IO, ConfirmedTransaction] =
        explorer.getConfirmedTransactions(addresses.toSeq, block)
    }

  implicit def unconfirmedTransaction(implicit
      cs: ContextShift[IO],
      t: Timer[IO]
  ): Recordable[UnconfirmedTransaction] =
    new Recordable[UnconfirmedTransaction] {
      override def fetch(
          explorer: ExplorerClient
      )(addresses: Set[Address], block: Option[BlockHash]): Stream[IO, UnconfirmedTransaction] =
        explorer.getUnconfirmedTransactions(addresses)
    }
}
