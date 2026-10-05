package madrileno.auth.routers.dto

import madrileno.auth.domain.{InternalJwt, RefreshTokenSecret}
import madrileno.utils.json.JsonProtocol.*

final case class AuthenticatedResponse(
  jwt: InternalJwt,
  refreshToken: RefreshTokenSecret,
  userCreated: Boolean)
    derives Encoder.AsObject,
      Decoder
