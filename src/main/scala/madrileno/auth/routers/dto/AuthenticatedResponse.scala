package madrileno.auth.routers.dto

import madrileno.auth.domain.{InternalJwt, RefreshTokenId}
import madrileno.utils.json.JsonProtocol.*

final case class AuthenticatedResponse(
  jwt: InternalJwt,
  refreshToken: RefreshTokenId,
  userCreated: Boolean)
    derives Encoder.AsObject,
      Decoder
