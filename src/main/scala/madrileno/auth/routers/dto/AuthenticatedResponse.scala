package madrileno.auth.routers.dto

import madrileno.auth.domain.{InternalJwt, RefreshTokenId}
import madrileno.utils.json.JsonProtocol.*

case class AuthenticatedResponse(jwt: InternalJwt, refreshToken: RefreshTokenId) derives Encoder.AsObject
