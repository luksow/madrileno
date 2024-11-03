package madrileno.main

import cats.effect.{Clock, IO}
import com.comcast.ip4s.{Ipv4Address, Port}
import madrileno.auth.AuthModule
import madrileno.healthcheck.HealthCheckModule
import madrileno.utils.db.transactor.Transactor
import madrileno.utils.http.{ApplicationRouteProvider, Handlers}
import madrileno.utils.observability.*
import org.typelevel.otel4s.Attribute
import org.http4s.Headers
import org.http4s.otel4s.middleware.instances.all.*
import pl.iterators.stir.server.{PathMatcher, Route}
import pureconfig.*
import pureconfig.generic.derivation.default.*
import pureconfig.module.ip4s.*
import sttp.capabilities.fs2.Fs2Streams
import sttp.client4.{WebSocketStreamBackend, logging}
import sttp.client4.logging.{LogLevel, LoggingBackend}

import java.util.UUID

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
    with HealthCheckModule {
  lazy val httpConfig: HttpConfig             = config.at("http").loadOrThrow[HttpConfig]
  lazy val appConfig: AppConfig               = config.at("app").loadOrThrow[AppConfig]
  lazy val telemetryContext: TelemetryContext = summon[TelemetryContext]

//  private val httpClientLogger: logging.Logger[IO] = new logging.Logger[IO] {
//    private def wrap(
//      level: LogLevel,
//      message: => String,
//      throwable: Option[Throwable],
//      context: Map[String, Any]
//    ): IO[Unit] = {
//      TracingContextSource[IO].update(_.withMoreContext(context.view.mapValues(_.toString).toList*)) *>
//        (level match {
//          case LogLevel.Trace =>
//            throwable.fold(Logger[IO].debug(message))(t => Logger[IO].warning(t)(message))
//          case LogLevel.Debug =>
//            throwable.fold(Logger[IO].debug(message))(t => Logger[IO].warning(t)(message))
//          case LogLevel.Info =>
//            throwable.fold(Logger[IO].info(message))(t => Logger[IO].warning(t)(message))
//          case LogLevel.Warn =>
//            throwable.fold(Logger[IO].warning(message))(t => Logger[IO].warning(t)(message))
//          case LogLevel.Error =>
//            throwable.fold(Logger[IO].error(message))(t => Logger[IO].error(t)(message))
//        })
//    }
//    override def apply(
//      level: LogLevel,
//      message: => String,
//      context: Map[String, Any]
//    ): IO[Unit] = wrap(level, message, None, context)
//
//    override def apply(
//      level: LogLevel,
//      message: => String,
//      t: Throwable,
//      context: Map[String, Any]
//    ): IO[Unit] = wrap(level, message, Some(t), context)
//  }
//  val httpClient: WebSocketStreamBackend[IO, Fs2Streams[IO]] =
//    CorrelationIdBackend(LoggingBackend(httpBackend, httpClientLogger), headerName = "Correlation-Id")

  private def logAction(initalCtx: Map[String, String]): String => IO[Unit] = {
    config.at("logging.loglevel-request-response").loadOrThrow[Int] match {
      case 4 => logger.debug(initalCtx)(_)
      case 3 => logger.info(initalCtx)(_)
      case 2 => logger.warn(initalCtx)(_)
      case 1 => logger.error(initalCtx)(_)
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
