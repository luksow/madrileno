package madrileno.auth.routers.dto

import madrileno.auth.domain.{FirebaseJwt, RefreshTokenSecret}
import madrileno.utils.json.JsonProtocol.*

final case class AuthWithFirebaseRequest(firebaseJwtToken: FirebaseJwt) derives Decoder, Encoder.AsObject

final case class AuthWithRefreshTokenRequest(refreshToken: RefreshTokenSecret) derives Decoder, Encoder.AsObject

final case class AuthWithEmailRequest(email: String) derives Decoder, Encoder.AsObject

final case class AuthWithOidcRequest(idToken: String) derives Decoder, Encoder.AsObject
