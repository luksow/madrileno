package madrileno.main

import cats.effect.{Clock, IO}
import com.comcast.ip4s.{Ipv4Address, Port}
import madrileno.auction.AuctionModule
import madrileno.auth.AuthModule
import madrileno.healthcheck.HealthCheckModule
import madrileno.user.UserModule
import madrileno.utils.cache.CacheRuntime
import madrileno.utils.db.transactor.Transactor
import madrileno.utils.events.EventBusRuntime
import madrileno.utils.http.{ApplicationRouteProvider, Handlers, RateLimiterRuntime}
import madrileno.utils.mailer.{MailContext, MailPreviewProvider, MailPreviewRouter, Mailer, MailerConfig, SmtpSender}
import madrileno.utils.observability.*
import madrileno.utils.storage.{ObjectStore, ObjectStoreRuntime, StorageConfig}
import madrileno.utils.task.{ApplicationTaskProvider, OneTimeTask, SchedulerAdminRouter, SchedulerClient}
import org.http4s.otel4s.middleware.instances.all.*
import org.http4s.server.websocket.WebSocketBuilder2
import org.http4s.{Headers, HttpRoutes, Response, Status}
import org.typelevel.otel4s.Attribute
import pl.iterators.baklava.http4s.routes.BaklavaRoutes
import pl.iterators.stir.server.directives.{CredentialsHelper, RouteDirectives}
import pl.iterators.stir.server.{PathMatcher, Route}
import pureconfig.*
import pureconfig.module.ip4s.*
import sttp.capabilities.fs2.Fs2Streams
import sttp.client4.logging.{LogConfig, LogLevel, Logger, LoggingBackend}
import sttp.client4.opentelemetry.OpenTelemetryMetricsBackend
import sttp.client4.{WebSocketStreamBackend, logging}

import java.net.URI

final case class HttpConfig(
  host: Ipv4Address,
  port: Port,
  maxRequestSize: Long,
  baseUrl: URI)
    derives ConfigReader
final case class AppConfig(
  name: String,
  environment: String,
  version: String,
  apiPrefix: String)
    derives ConfigReader
final case class AdminConfig(user: String, password: String) derives ConfigReader

class ApplicationLoader(
  val config: ConfigSource,
  httpBackend: WebSocketStreamBackend[IO, Fs2Streams[IO]],
  val transactor: Transactor,
  val clock: Clock[IO],
  val schedulerClient: SchedulerClient,
  val cacheRuntime: CacheRuntime,
  val rateLimiterRuntime: RateLimiterRuntime,
  val objectStoreRuntime: ObjectStoreRuntime,
  val eventBusRuntime: EventBusRuntime
)(using TelemetryContext)
    extends ApplicationRouteProvider
    with ApplicationTaskProvider
    with MailPreviewProvider
    with LoggingSupport
    with Handlers
    with AuthModule
    with UserModule
    with AuctionModule
    with HealthCheckModule {
  lazy val httpConfig: HttpConfig             = config.at("http").loadOrThrow[HttpConfig]
  lazy val appConfig: AppConfig               = config.at("app").loadOrThrow[AppConfig]
  lazy val adminConfig: AdminConfig           = config.at("admin").loadOrThrow[AdminConfig]
  lazy val storageConfig: StorageConfig       = config.at("storage").loadOrThrow[StorageConfig]
  lazy val telemetryContext: TelemetryContext = summon[TelemetryContext]
  lazy val mailContext: MailContext           = MailContext(httpConfig.baseUrl)
  val objectStore: ObjectStore                = objectStoreRuntime.objectStore

  protected lazy val mailerConfig: MailerConfig = config.at("mailer").loadOrThrow[MailerConfig]
  private lazy val smtpSender                   = new SmtpSender(mailerConfig)
  lazy val mailer: Mailer                       = new Mailer(smtpSender, schedulerClient, mailContext)

  private lazy val baklavaHttpRoutes: HttpRoutes[IO] = BaklavaRoutes.routes()
  private val baklavaPathSegments: Set[String]       = Set("openapi", "swagger", "swagger-ui", "docs")

  lazy val baklavaDocs: Route = httpRoutesOf {
    case req if baklavaPathSegments.contains(req.uri.path.segments.headOption.fold("")(_.encoded)) =>
      baklavaHttpRoutes.run(req).getOrElseF(IO.pure(Response[IO](Status.NotFound)))
  }

  private val adminAuthenticator: CredentialsHelper => Option[Unit] = {
    case p @ CredentialsHelper.Provided(id) if id == adminConfig.user && p.verify(adminConfig.password) => Some(())
    case _                                                                                              => None
  }

  lazy val schedulerAdminRouter: SchedulerAdminRouter = new SchedulerAdminRouter(recurringTasks, oneTimeTasks, customTasks, schedulerClient)

  lazy val adminRoutes: Route = pathPrefix("admin") {
    authenticateBasic(realm = "madrileno-admin", authenticator = adminAuthenticator) { _ =>
      adminRoute ~ schedulerAdminRouter.routes
    }
  }

  override def oneTimeTasks: List[OneTimeTask[?]] = super.oneTimeTasks :+ mailer.sendMailTask

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

  private val apiPrefix: String                    = appConfig.apiPrefix
  private val pathPrefixMatcher: PathMatcher[Unit] = Slash ~ apiPrefix

  def routes(wsb: WebSocketBuilder2[IO]): Route =
    onSuccess(telemetryContext.tracer.propagate(Map.empty)) { initialCtx =>
      logRequest(logAction = Some(logAction(initialCtx))) {
        handleExceptions(exceptionHandler(logResult(logAction = Some(logAction(initialCtx))))) {
          handleRejections(rejectionHandler(logResult(logAction = Some(logAction(initialCtx))))) {
            rawPathPrefix(pathPrefixMatcher) {
              authenticateOrRejectWithChallenge(userAuthenticator) { auth =>
                handleExceptions(exceptionHandler(logResult(logAction = Some(logAction(initialCtx))))) {
                  handleRejections(rejectionHandler(logResult(logAction = Some(logAction(initialCtx))))) {
                    onSuccess(telemetryContext.tracer.currentSpanOrNoop.flatMap(_.addAttribute(Attribute("app.user.id", auth.userId.toString)))) {
                      logResult(logAction = Some(logAction(initialCtx))) {
                        onSuccess(telemetryContext.tracer.propagate(Headers.empty)) { newHeaders =>
                          mapResponseHeaders(_ ++ newHeaders) {
                            route(auth) ~ route ~ wsRoutes(auth, wsb) ~ wsRoutes(wsb)
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
                      route ~ wsRoutes(wsb)
                    }
                  }
                }
            } ~ adminRoutes ~ (if (appConfig.environment == "dev") new MailPreviewRouter(mailPreviews, mailContext).routes ~ baklavaDocs
                               else RouteDirectives.reject)
          }
        }
      }
    }
}
