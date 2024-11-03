package madrileno.utils.observability

import cats.effect.IO
import org.typelevel.log4cats.SelfAwareStructuredLogger
import org.typelevel.log4cats.slf4j.Slf4jLogger

trait LoggingSupport {
  def logger(using tc: TelemetryContext): SelfAwareStructuredLogger[IO] = new SelfAwareStructuredLogger[IO] {
    override def isTraceEnabled: IO[Boolean] = _logger.isTraceEnabled

    override def isDebugEnabled: IO[Boolean] = _logger.isDebugEnabled

    override def isInfoEnabled: IO[Boolean] = _logger.isInfoEnabled

    override def isWarnEnabled: IO[Boolean] = _logger.isWarnEnabled

    override def isErrorEnabled: IO[Boolean] = _logger.isErrorEnabled

    override def trace(ctx: Map[String, String])(msg: => String): IO[Unit] = propagateContext(ctx)(_logger.trace(_)(msg))

    override def trace(ctx: Map[String, String], t: Throwable)(msg: => String): IO[Unit] = propagateContext(ctx)(_logger.trace(_, t)(msg))

    override def debug(ctx: Map[String, String])(msg: => String): IO[Unit] = propagateContext(ctx)(_logger.debug(_)(msg))

    override def debug(ctx: Map[String, String], t: Throwable)(msg: => String): IO[Unit] = propagateContext(ctx)(_logger.debug(_, t)(msg))

    override def info(ctx: Map[String, String])(msg: => String): IO[Unit] = propagateContext(ctx)(_logger.info(_)(msg))

    override def info(ctx: Map[String, String], t: Throwable)(msg: => String): IO[Unit] = propagateContext(ctx)(_logger.info(_, t)(msg))

    override def warn(ctx: Map[String, String])(msg: => String): IO[Unit] = propagateContext(ctx)(_logger.warn(_)(msg))

    override def warn(ctx: Map[String, String], t: Throwable)(msg: => String): IO[Unit] = propagateContext(ctx)(_logger.warn(_, t)(msg))

    override def error(ctx: Map[String, String])(msg: => String): IO[Unit] = propagateContext(ctx)(_logger.error(_)(msg))

    override def error(ctx: Map[String, String], t: Throwable)(msg: => String): IO[Unit] = propagateContext(ctx)(_logger.error(_, t)(msg))

    override def error(t: Throwable)(message: => String): IO[Unit] = propagateNewContext(_logger.error(_, t)(message))

    override def warn(t: Throwable)(message: => String): IO[Unit] = propagateNewContext(_logger.warn(_, t)(message))

    override def info(t: Throwable)(message: => String): IO[Unit] = propagateNewContext(_logger.info(_, t)(message))

    override def debug(t: Throwable)(message: => String): IO[Unit] = propagateNewContext(_logger.debug(_, t)(message))

    override def trace(t: Throwable)(message: => String): IO[Unit] = propagateNewContext(_logger.trace(_, t)(message))

    override def error(message: => String): IO[Unit] = propagateNewContext(_logger.error(_)(message))

    override def warn(message: => String): IO[Unit] = propagateNewContext(_logger.warn(_)(message))

    override def info(message: => String): IO[Unit] = propagateNewContext(_logger.info(_)(message))

    override def debug(message: => String): IO[Unit] = propagateNewContext(_logger.debug(_)(message))

    override def trace(message: => String): IO[Unit] = propagateNewContext(_logger.trace(_)(message))

    private def propagateContext(ctx: Map[String, String])(withNewCtx: Map[String, String] => IO[Unit]): IO[Unit] = {
      tc.tracer.propagate(ctx).flatMap { newCtx =>
        withNewCtx(newCtx)
      }
    }

    private def propagateNewContext(withNewCtx: Map[String, String] => IO[Unit]): IO[Unit] = propagateContext(Map.empty)(withNewCtx)
  }

  def loggerWithoutTracing: SelfAwareStructuredLogger[IO] = _logger

  private val _logger: SelfAwareStructuredLogger[IO] = Slf4jLogger.getLoggerFromClass[IO](getClass)
}
