package madrileno.auth.routers.dto

import madrileno.auth.domain.FirebaseJwt
import madrileno.utils.json.JsonProtocol.*

case class AuthWithFirebaseRequest(firebaseJwtToken: FirebaseJwt) derives Decoder
