package madrileno.auth.routers.dto

import madrileno.auth.domain.{FirebaseJwt, RefreshTokenId}
import madrileno.utils.json.JsonProtocol.*

case class AuthWithFirebaseRequest(firebaseJwtToken: FirebaseJwt) derives Decoder

case class AuthWithRefreshTokenRequest(refreshToken: RefreshTokenId) derives Decoder
