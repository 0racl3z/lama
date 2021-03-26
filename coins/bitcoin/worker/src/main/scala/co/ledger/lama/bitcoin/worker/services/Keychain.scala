package co.ledger.lama.bitcoin.worker.services

import cats.effect.IO
import co.ledger.lama.bitcoin.common.clients.grpc.KeychainClient
import co.ledger.lama.bitcoin.common.models.interpreter.AccountAddress
import co.ledger.lama.bitcoin.worker.services.Keychain.{Address, KeychainId, addressesRanges}
import co.ledger.lama.common.logging.DefaultContextLogging
import fs2.Stream

import java.util.UUID

class Keychain(client: KeychainClient) extends DefaultContextLogging {

  def markAsUsed(id: KeychainId, addresses: Set[Address]): IO[Unit] =
    client
      .markAddressesAsUsed(id, addresses.toList)

  def addresses(id: KeychainId): Stream[IO, List[AccountAddress]] = {
    Stream
      .eval(client.getKeychainInfo(id))
      .flatMap(i => addressesRanges(i.lookaheadSize))
      .evalMap(r => client.getAddresses(id, r.head, r.last + 1))
  }
}

object Keychain {
  type Address    = String
  type KeychainId = UUID

  def addressesRanges[F[_]](size: Int): Stream[F, Range] = {

    val from = Stream.iterate(0)(_ + size)
    val to   = Stream.iterate(size)(_ + size)

    from
      .zip(to)
      .map(r => r._1 until r._2)
  }
}
