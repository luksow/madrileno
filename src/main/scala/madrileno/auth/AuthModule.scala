package madrileno.auth

import com.softwaremill.macwire.*
import madrileno.auth.routers.UserAuthenticator
import madrileno.auth.services.JwtService
import pureconfig.ConfigSource

trait AuthModule {
  val config: ConfigSource
  val jwtConfig: JwtService.Config = config.at("jwt").loadOrThrow[JwtService.Config]
  private val jwtService           = wire[JwtService]
  val userAuthenticator            = wire[UserAuthenticator]
}
