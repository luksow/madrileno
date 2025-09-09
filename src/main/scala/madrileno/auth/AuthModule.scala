package madrileno.auth

import com.google.auth.oauth2.GoogleCredentials
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.{FirebaseApp, FirebaseOptions}
import com.softwaremill.macwire.*
import madrileno.auth.domain.AuthContext
import madrileno.auth.repositories.{RefreshTokenRowRepository, UserAuthRowRepository}
import madrileno.auth.routers.{AuthRouter, UserAuthenticator}
import madrileno.auth.services.*
import madrileno.user.repositories.UserRowRepository
import madrileno.utils.db.transactor.Transactor
import madrileno.utils.http.{AuthRouteProvider, RouteProvider}
import madrileno.utils.observability.TelemetryContext
import pl.iterators.stir.server.Route
import pureconfig.ConfigSource

import java.io.ByteArrayInputStream
import scala.util.Try

trait AuthModule extends RouteProvider with AuthRouteProvider {
  val config: ConfigSource
  val jwtConfig: JwtService.Config = config.at("jwt").loadOrThrow[JwtService.Config]
  private val jwtService           = wire[JwtService]
  given telemetryContext: TelemetryContext
  val transactor: Transactor
  lazy val userRowRepository: UserRowRepository

  val userAuthenticator: UserAuthenticator = wire[UserAuthenticator]

  private val firebaseKey = config.at("firebase.key").loadOrThrow[String]
  private val firebaseApp = Try {
    val serviceAccount = new ByteArrayInputStream(firebaseKey.getBytes())
    val options = FirebaseOptions
      .builder()
      .setCredentials(GoogleCredentials.fromStream(serviceAccount))
      .build()

    FirebaseApp.initializeApp(options)
  }.fold(e => throw new RuntimeException("Failed to initialize Firebase", e), identity)
  private val firebaseAuth    = FirebaseAuth.getInstance(firebaseApp)
  private val firebaseService = wire[FirebaseService]

  private val userAuthRowRepository     = wire[UserAuthRowRepository]
  private val refreshTokenRowRepository = wire[RefreshTokenRowRepository]
  private val authenticationService     = wire[AuthenticationService]
  private val authRouter                = wire[AuthRouter]

  override abstract def route(auth: AuthContext): Route = {
    super.route(auth) ~ authRouter.authedRoutes(auth)
  }

  override abstract def route: Route = {
    super.route ~ authRouter.routes
  }
}
