package madrileno.auth.domain

import io.circe.{Decoder, Encoder, Json}
import madrileno.user.domain.*
import pl.iterators.kebs.opaque.Opaque

import java.net.URI

opaque type InternalJwt = String
object InternalJwt extends Opaque[InternalJwt, String]

final case class AuthContext(
  userId: UserId,
  fullName: Option[FullName],
  avatarUrl: Option[URI]) {}

object AuthContext {
  def apply(user: User): AuthContext = {
    AuthContext(user.id, user.fullName, user.avatarUrl)
  }

  import pl.iterators.kebs.circe.*
  given Decoder[AuthContext] = Decoder.derived[AuthContext]
  given Encoder[AuthContext] = Encoder.AsObject[AuthContext]

  def from(json: Json): Either[String, AuthContext] = summon[Decoder[AuthContext]].decodeJson(json).left.map(_.toString)

  def toJson(authContext: AuthContext): Json = summon[Encoder[AuthContext]](authContext)

}
