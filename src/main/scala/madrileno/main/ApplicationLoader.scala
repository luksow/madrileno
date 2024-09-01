package madrileno.main

import cats.effect.{Clock, IO}
import com.comcast.ip4s.{Ipv4Address, Port}
import madrileno.auth.AuthModule
import madrileno.healthcheck.HealthCheckModule
import madrileno.utils.db.transactor.Transactor
import madrileno.utils.http.{ApplicationRouteProvider, Handlers}
import madrileno.utils.logging.*
import madrileno.utils.logging.CorrelationIdDirectives.withCorrelationId
import pl.iterators.stir.server.{PathMatcher, Route}
import pureconfig.*
import pureconfig.generic.derivation.default.*
import pureconfig.module.ip4s.*
import sttp.capabilities.fs2.Fs2Streams
import sttp.client4.{WebSocketStreamBackend, logging}
import sttp.client4.logging.{LogLevel, LoggingBackend}

import java.util.UUID

final case class HttpConfig(host: Ipv4Address, port: Port) derives ConfigReader
final case class AppConfig(
  name: String,
  environment: String,
  version: String,
  apiVersion: String)
    derives ConfigReader

class ApplicationLoader(
  val config: ConfigSource,
  httpBackend: WebSocketStreamBackend[IO, Fs2Streams[IO]],
  val transactor: Transactor,
  val clock: Clock[IO])
    extends ApplicationRouteProvider
    with LoggingSupport
    with Handlers
    with AuthModule
    with HealthCheckModule {
  lazy val httpConfig: HttpConfig = config.at("http").loadOrThrow[HttpConfig]
  lazy val appConfig: AppConfig   = config.at("app").loadOrThrow[AppConfig]

  private val httpClientLogger: logging.Logger[IO] = new logging.Logger[IO] {
    private def wrap(
      level: LogLevel,
      message: => String,
      throwable: Option[Throwable],
      context: Map[String, Any]
    ): IO[Unit] = {
      TracingContextSource[IO].update(_.withMoreContext(context.view.mapValues(_.toString).toList*)) *>
        (level match {
          case LogLevel.Trace =>
            throwable.fold(Logger[IO].debug(message))(t => Logger[IO].warning(t)(message))
          case LogLevel.Debug =>
            throwable.fold(Logger[IO].debug(message))(t => Logger[IO].warning(t)(message))
          case LogLevel.Info =>
            throwable.fold(Logger[IO].info(message))(t => Logger[IO].warning(t)(message))
          case LogLevel.Warn =>
            throwable.fold(Logger[IO].warning(message))(t => Logger[IO].warning(t)(message))
          case LogLevel.Error =>
            throwable.fold(Logger[IO].error(message))(t => Logger[IO].error(t)(message))
        })
    }
    override def apply(
      level: LogLevel,
      message: => String,
      context: Map[String, Any]
    ): IO[Unit] = wrap(level, message, None, context)

    override def apply(
      level: LogLevel,
      message: => String,
      t: Throwable,
      context: Map[String, Any]
    ): IO[Unit] = wrap(level, message, Some(t), context)
  }
  val httpClient: WebSocketStreamBackend[IO, Fs2Streams[IO]] =
    CorrelationIdBackend(LoggingBackend(httpBackend, httpClientLogger), headerName = "Correlation-Id")

  private val logAction: String => IO[Unit] = {
    config.at("logging.loglevel-request-response").loadOrThrow[Int] match {
      case 4 => Logger[IO].debug(_)
      case 3 => Logger[IO].info(_)
      case 2 => Logger[IO].warning(_)
      case 1 => Logger[IO].error(_)
    }
  }

  val apiVersion: String                   = appConfig.apiVersion
  val pathPrefixMatcher: PathMatcher[Unit] = Slash ~ apiVersion

  val routes: Route =
    withCorrelationId(headerName = "Correlation-Id", correlationIdGenerator = UUID.randomUUID().toString) { correlationId =>
      val tracingContext: TracingContext = TracingContext("context", correlationId, Map.empty)
      onSuccess(TracingContext.set(tracingContext)) {
        logRequest(logAction = Some(logAction)) {
          handleExceptions(exceptionHandler(Some(tracingContext), logResult(logAction = Some(logAction)))) {
            handleRejections(rejectionHandler(Some(tracingContext), logResult(logAction = Some(logAction)))) {
              rawPathPrefix(pathPrefixMatcher) {
                authenticateOrRejectWithChallenge(userAuthenticator) { auth =>
                  val userTracingContext = tracingContext.withMoreContext("uid" -> auth.id.toString)
                  onSuccess(TracingContext.set(userTracingContext)) {
                    handleExceptions(exceptionHandler(Some(tracingContext), logResult(logAction = Some(logAction)))) {
                      handleRejections(rejectionHandler(Some(tracingContext), logResult(logAction = Some(logAction)))) {
                        logResult(logAction = Some(logAction)) {
                          route(auth) ~ route
                        }
                      }
                    }
                  }
                } ~ logResult(logAction = Some(logAction)) {
                  route
                }
              }
            }
          }
        }
      }
    }
}
