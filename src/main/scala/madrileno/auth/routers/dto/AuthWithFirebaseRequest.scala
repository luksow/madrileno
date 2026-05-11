package madrileno.auth.routers.dto

import madrileno.auth.domain.{FirebaseJwt, RefreshTokenId}
import madrileno.utils.json.JsonProtocol.*

final case class AuthWithFirebaseRequest(firebaseJwtToken: FirebaseJwt) derives Decoder, Encoder.AsObject

final case class AuthWithRefreshTokenRequest(refreshToken: RefreshTokenId) derives Decoder, Encoder.AsObject
