package madrileno.utils.logging

import org.slf4j
import org.slf4j.{LoggerFactory, MDC}

trait LoggingSupport {
  private lazy val cls: Class[? <: LoggingSupport] = getClass
  lazy val logger: slf4j.Logger                    = LoggerFactory.getLogger(cls)

  trait Logger[F[_]] {
    def error(message: => String): F[Unit]
    def warning(message: => String): F[Unit]
    def info(message: => String): F[Unit]
    def debug(message: => String): F[Unit]
    def error(t: Throwable)(message: => String): F[Unit]
    def warning(t: Throwable)(message: => String): F[Unit]
  }

  object Logger {
    private def withMDC[F[_]: TracingContextSource, T](t: => T): F[T] =
      summon[TracingContextSource[F]].map { ctx =>
        ctx.foreach(c => MDC.put(c.contextName, c.context))
        try t
        finally ctx.foreach(c => MDC.remove(c.contextName))
      }

    def apply[F[_]: TracingContextSource]: Logger[F] = new Logger[F] {
      override def error(message: => String): F[Unit] = withMDC(logger.error(message))

      override def warning(message: => String): F[Unit] = withMDC(logger.warn(message))

      override def info(message: => String): F[Unit] = withMDC(logger.info(message))

      override def debug(message: => String): F[Unit] = withMDC(logger.debug(message))

      override def error(t: Throwable)(message: => String): F[Unit] = withMDC(logger.error(message, t))

      override def warning(t: Throwable)(message: => String): F[Unit] = withMDC(logger.warn(message, t))

    }
  }
}
