package madrileno.main

import cats.effect.{Clock, IO}
import com.comcast.ip4s.{Ipv4Address, Port}
import madrileno.auth.AuthModule
import madrileno.healthcheck.HealthCheckModule
import madrileno.user.UserModule
import madrileno.utils.db.transactor.Transactor
import madrileno.utils.http.{ApplicationRouteProvider, Handlers}
import madrileno.utils.observability.*
import org.http4s.Headers
import org.http4s.otel4s.middleware.instances.all.*
import org.typelevel.otel4s.Attribute
import pl.iterators.stir.server.{PathMatcher, Route}
import pureconfig.*
import pureconfig.module.ip4s.*
import sttp.capabilities.fs2.Fs2Streams
import sttp.client4.logging.{LogConfig, LogLevel, Logger, LoggingBackend}
import sttp.client4.opentelemetry.OpenTelemetryMetricsBackend
import sttp.client4.{WebSocketStreamBackend, logging}

final case class HttpConfig(
  host: Ipv4Address,
  port: Port,
  maxRequestSize: Long)
    derives ConfigReader
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
  val clock: Clock[IO]
)(using TelemetryContext)
    extends ApplicationRouteProvider
    with LoggingSupport
    with Handlers
    with AuthModule
    with UserModule
    with HealthCheckModule {
  lazy val httpConfig: HttpConfig             = config.at("http").loadOrThrow[HttpConfig]
  lazy val appConfig: AppConfig               = config.at("app").loadOrThrow[AppConfig]
  lazy val telemetryContext: TelemetryContext = summon[TelemetryContext]

  private lazy val httpClientLogger: logging.Logger[IO] = new Logger[IO] {
    override def apply(
      level: LogLevel,
      message: => String,
      throwable: Option[Throwable],
      context: Map[String, Any]
    ): IO[Unit] = {
      val contextMapped = context.view.mapValues(_.toString).toMap
      level match {
        case LogLevel.Trace => throwable.fold(logger.trace(contextMapped)(message))(logger.trace(contextMapped, _)(message))
        case LogLevel.Debug => throwable.fold(logger.debug(contextMapped)(message))(logger.debug(contextMapped, _)(message))
        case LogLevel.Info  => throwable.fold(logger.info(contextMapped)(message))(logger.info(contextMapped, _)(message))
        case LogLevel.Warn  => throwable.fold(logger.warn(contextMapped)(message))(logger.warn(contextMapped, _)(message))
        case LogLevel.Error => throwable.fold(logger.error(contextMapped)(message))(logger.error(contextMapped, _)(message))
      }
    }
  }

  lazy val httpClient: WebSocketStreamBackend[IO, Fs2Streams[IO]] =
    OpenTelemetryTracingBackend(
      OpenTelemetryMetricsBackend(
        LoggingBackend(httpBackend, httpClientLogger, LogConfig.Default.copy(logRequestBody = true, logResponseBody = true)),
        telemetryContext.underlying
      )
    )

  private def logAction(initialCtx: Map[String, String]): String => IO[Unit] = {
    config.at("logging.loglevel-request-response").loadOrThrow[Int] match {
      case 4 => logger.debug(initialCtx)(_)
      case 3 => logger.info(initialCtx)(_)
      case 2 => logger.warn(initialCtx)(_)
      case 1 => logger.error(initialCtx)(_)
    }
  }

  private val apiVersion: String                   = appConfig.apiVersion
  private val pathPrefixMatcher: PathMatcher[Unit] = Slash ~ apiVersion

  val routes: Route =
    onSuccess(telemetryContext.tracer.propagate(Map.empty)) { initialCtx =>
      logRequest(logAction = Some(logAction(initialCtx))) {
        handleExceptions(exceptionHandler(logResult(logAction = Some(logAction(initialCtx))))) {
          handleRejections(rejectionHandler(logResult(logAction = Some(logAction(initialCtx))))) {
            rawPathPrefix(pathPrefixMatcher) {
              authenticateOrRejectWithChallenge(userAuthenticator) { auth =>
                handleExceptions(exceptionHandler(logResult(logAction = Some(logAction(initialCtx))))) {
                  handleRejections(rejectionHandler(logResult(logAction = Some(logAction(initialCtx))))) {
                    onSuccess(telemetryContext.tracer.currentSpanOrNoop.flatMap(_.addAttribute(Attribute("app.user.id", auth.id.toString)))) {
                      logResult(logAction = Some(logAction(initialCtx))) {
                        onSuccess(telemetryContext.tracer.propagate(Headers.empty)) { newHeaders =>
                          mapResponseHeaders(_ ++ newHeaders) {
                            route(auth) ~ route
                          }
                        }
                      }
                    }
                  }
                }
              } ~
                logResult(logAction = Some(logAction(initialCtx))) {
                  onSuccess(telemetryContext.tracer.propagate(Headers.empty)) { newHeaders =>
                    mapResponseHeaders(_ ++ newHeaders) {
                      route
                    }
                  }
                }
            }
          }
        }
      }
    }
}
