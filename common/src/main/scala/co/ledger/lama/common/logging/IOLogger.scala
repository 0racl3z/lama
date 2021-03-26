package co.ledger.lama.common.logging

import cats.effect.IO
import com.typesafe.scalalogging.LoggerTakingImplicit

case class IOLogger(logger: LoggerTakingImplicit[LogContext]) {

  // TRACE

  def trace(message: String)(implicit context: LogContext): IO[Unit] =
    IO(logger.trace(message))

  def trace(message: String, cause: Throwable)(implicit context: LogContext): IO[Unit] =
    IO(logger.trace(message, cause))

  // DEBUG

  def debug(message: => String)(implicit context: LogContext): IO[Unit] =
    IO(logger.debug(message))

  def debug(message: => String, cause: Throwable)(implicit context: LogContext): IO[Unit] =
    IO(logger.debug(message, cause))

  // INFO

  def info(message: String)(implicit context: LogContext): IO[Unit] =
    IO(logger.info(message))

  def info(message: String, cause: Throwable)(implicit context: LogContext): IO[Unit] =
    IO(logger.info(message, cause))

  // WARN

  def warn(message: String)(implicit context: LogContext): IO[Unit] =
    IO(logger.warn(message))

  def warn(message: String, cause: Throwable)(implicit context: LogContext): IO[Unit] =
    IO(logger.warn(message, cause))

  // ERROR

  def error(message: String)(implicit context: LogContext): IO[Unit] =
    IO(logger.error(message))

  def error(message: String, cause: Throwable)(implicit context: LogContext): IO[Unit] =
    IO(logger.error(message, cause))

}
