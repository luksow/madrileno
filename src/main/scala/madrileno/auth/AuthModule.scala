package madrileno.auth

import com.google.auth.oauth2.GoogleCredentials
import com.google.firebase.{FirebaseApp, FirebaseOptions}
import com.google.firebase.auth.FirebaseAuth
import com.softwaremill.macwire.*
import madrileno.auth.routers.{AuthRouter, UserAuthenticator}
import madrileno.auth.services.*
import madrileno.utils.http.RouteProvider
import madrileno.utils.observability.TelemetryContext
import pl.iterators.stir.server.Route
import pureconfig.ConfigSource

import java.io.ByteArrayInputStream
import scala.util.Try

trait AuthModule extends RouteProvider {
  val config: ConfigSource
  val jwtConfig: JwtService.Config = config.at("jwt").loadOrThrow[JwtService.Config]
  private val jwtService           = wire[JwtService]
  given telemetryContext: TelemetryContext
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

  private val authenticationService  = wire[AuthenticationService]
  private val authRouter: AuthRouter = wire[AuthRouter]

  override abstract def route: Route = super.route ~ authRouter.routes
}
