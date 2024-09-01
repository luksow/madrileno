package madrileno.auth.domain

import io.circe.{Decoder, Encoder, Json}
import madrileno.user.domain.*

final case class AuthContext(id: UserId, emailAddress: EmailAddress)

object AuthContext {
  def apply(json: Json): Either[String, AuthContext] = authContextDecoder.decodeJson(json).left.map(_.toString)
  def toJson(authContext: AuthContext): Json         = authContextEncoder(authContext)

  import pl.iterators.kebs.circe.*
  private val authContextDecoder = Decoder.derived[AuthContext]
  private val authContextEncoder = Encoder.AsObject[AuthContext]
}
