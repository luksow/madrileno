package madrileno.auth.domain

import io.circe.{Decoder, Encoder, Json}
import madrileno.user.domain.*
import pl.iterators.kebs.opaque.Opaque

import java.net.URI

opaque type InternalJwt = String
object InternalJwt extends Opaque[InternalJwt, String]

final case class AuthContext(
  id: UserId,
  fullName: Option[FullName],
  avatarUrl: Option[URI]) {}

object AuthContext {
  def from(json: Json): Either[String, AuthContext] = authContextDecoder.decodeJson(json).left.map(_.toString)
  def toJson(authContext: AuthContext): Json        = authContextEncoder(authContext)

  def apply(user: User): AuthContext = {
    AuthContext(user.id, user.fullName, user.avatarUrl)
  }

  import pl.iterators.kebs.circe.*
  private val authContextDecoder = Decoder.derived[AuthContext]
  private val authContextEncoder = Encoder.AsObject[AuthContext]
}
