package co.ledger.lama.common.logging

import cats.effect.IO
import com.typesafe.scalalogging.LoggerTakingImplicit

case class IOLogger(logger: LoggerTakingImplicit[LogContext]) {

  // TRACE

  def trace(message: String)(implicit context: LogContext): IO[Unit] =
    IO(logger.trace(message)).attempt *> IO.unit

  def trace(message: String, cause: Throwable)(implicit context: LogContext): IO[Unit] =
    IO(logger.trace(message, cause)).attempt *> IO.unit

  // DEBUG

  def debug(message: => String)(implicit context: LogContext): IO[Unit] =
    IO(logger.debug(message)).attempt *> IO.unit

  def debug(message: => String, cause: Throwable)(implicit context: LogContext): IO[Unit] =
    IO(logger.debug(message, cause)).attempt *> IO.unit

  // INFO

  def info(message: String)(implicit context: LogContext): IO[Unit] =
    IO(logger.info(message)).attempt *> IO.unit

  def info(message: String, cause: Throwable)(implicit context: LogContext): IO[Unit] =
    IO(logger.info(message, cause)).attempt *> IO.unit

  // WARN

  def warn(message: String)(implicit context: LogContext): IO[Unit] =
    IO(logger.warn(message)).attempt *> IO.unit

  def warn(message: String, cause: Throwable)(implicit context: LogContext): IO[Unit] =
    IO(logger.warn(message, cause)).attempt *> IO.unit

  // ERROR

  def error(message: String)(implicit context: LogContext): IO[Unit] =
    IO(logger.error(message)).attempt *> IO.unit

  def error(message: String, cause: Throwable)(implicit context: LogContext): IO[Unit] =
    IO(logger.error(message, cause)).attempt *> IO.unit

}
