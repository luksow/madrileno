package madrileno.auth

import cats.effect.IO
import com.softwaremill.macwire.*
import madrileno.auth.domain.{AuthContext, Provider}
import madrileno.auth.emails.WelcomeEmailTemplate
import madrileno.auth.repositories.*
import madrileno.auth.routers.{AuthRouter, UserAuthenticator}
import madrileno.auth.services.*
import madrileno.user.repositories.UserRepository
import madrileno.utils.cache.CacheRuntime
import madrileno.utils.db.transactor.Transactor
import madrileno.utils.http.{AuthRouteProvider, RouteProvider}
import madrileno.utils.mailer.{MailPreview, MailPreviewProvider, Mailer}
import madrileno.utils.observability.TelemetryContext
import madrileno.utils.task.{RecurringTaskProvider, Task}
import pl.iterators.stir.server.Route
import pureconfig.*
import sttp.capabilities.fs2.Fs2Streams
import sttp.client4.WebSocketStreamBackend

trait AuthModule extends RouteProvider with AuthRouteProvider with RecurringTaskProvider with MailPreviewProvider {
  val config: ConfigSource
  val jwtConfig: JwtService.Config = config.at("jwt").loadOrThrow[JwtService.Config]
  private val jwtService           = wire[JwtService]
  given telemetryContext: TelemetryContext
  val transactor: Transactor
  val cacheRuntime: CacheRuntime
  lazy val httpClient: WebSocketStreamBackend[IO, Fs2Streams[IO]]
  lazy val userRepository: UserRepository
  lazy val mailer: Mailer

  val userAuthenticator: UserAuthenticator = wire[UserAuthenticator]

  protected lazy val firebaseConfig: FirebaseConfig = config.at("firebase").loadOrThrow[FirebaseConfig]
  protected lazy val devAuthConfig: DevAuthConfig   = config.at("dev-auth").loadOrThrow[DevAuthConfig]

  protected lazy val externalAuthVerifiers: AuthVerifiers = {
    val firebase: Option[(Provider, ExternalAuthVerifier)] =
      firebaseConfig.projectId
        .filter(_.nonEmpty)
        .map(projectId => Provider.Firebase -> new FirebaseService(projectId, new FirebaseKeyProvider(httpClient)))
    val dev: Option[(Provider, ExternalAuthVerifier)] =
      Option.when(devAuthConfig.enabled)(Provider.Dev -> DevAuthVerifier)
    AuthVerifiers(List(firebase, dev).flatten.toMap)
  }

  private val userAuthRepository     = wire[UserAuthRepository]
  private val refreshTokenRepository = wire[RefreshTokenRepository]
  private val authenticationService  = wire[AuthenticationService]
  private val authRouter             = wire[AuthRouter]

  override abstract def route(auth: AuthContext): Route = {
    super.route(auth) ~ authRouter.authedRoutes(auth)
  }

  override abstract def route: Route = {
    super.route ~ authRouter.routes
  }

  override abstract def recurringTasks: List[Task[?]] = {
    super.recurringTasks :+ authenticationService.cleanupExpiredRefreshTokensTask
  }

  override abstract def mailPreviews: List[MailPreview] = {
    super.mailPreviews :+ WelcomeEmailTemplate.preview
  }
}

final case class FirebaseConfig(projectId: Option[String]) derives ConfigReader

final case class DevAuthConfig(enabled: Boolean) derives ConfigReader
