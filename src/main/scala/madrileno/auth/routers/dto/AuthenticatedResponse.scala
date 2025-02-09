package madrileno.auth.routers.dto

import madrileno.utils.json.JsonProtocol.*
import madrileno.auth.domain.InternalJwt

case class AuthenticatedResponse(jwt: InternalJwt) derives Encoder.AsObject
