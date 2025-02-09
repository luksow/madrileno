package madrileno.auth.routers.dto

import madrileno.utils.json.JsonProtocol.*
import madrileno.auth.domain.FirebaseJwtToken

case class AuthWithFirebaseRequest(firebaseJwtToken: FirebaseJwtToken) derives Decoder
