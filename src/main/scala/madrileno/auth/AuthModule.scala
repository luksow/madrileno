package madrileno.auth

import com.softwaremill.macwire.*
import madrileno.auth.routers.UserAuthenticator
import madrileno.auth.services.JwtService
import madrileno.utils.observability.TelemetryContext
import pureconfig.ConfigSource

trait AuthModule {
  val config: ConfigSource
  val jwtConfig: JwtService.Config = config.at("jwt").loadOrThrow[JwtService.Config]
  private val jwtService           = wire[JwtService]
  given telemetryContext: TelemetryContext
  val userAuthenticator: UserAuthenticator = wire[UserAuthenticator]
}
