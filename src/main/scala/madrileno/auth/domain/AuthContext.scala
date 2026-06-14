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
  avatarUrl: Option[URI],
  emailVerified: Boolean) {}

object AuthContext {
  def apply(user: User): AuthContext = {
    AuthContext(user.id, user.fullName, user.avatarUrl, user.emailVerified)
  }

  import pl.iterators.kebs.circe.*
  given Decoder[AuthContext] = Decoder.instance { c =>
    for {
      userId        <- c.get[UserId]("userId")
      fullName      <- c.get[Option[FullName]]("fullName")
      avatarUrl     <- c.get[Option[URI]]("avatarUrl")
      emailVerified <- c.getOrElse[Boolean]("emailVerified")(false)
    } yield AuthContext(userId, fullName, avatarUrl, emailVerified)
  }
  given Encoder[AuthContext] = Encoder.AsObject[AuthContext]

  def from(json: Json): Either[String, AuthContext] = summon[Decoder[AuthContext]].decodeJson(json).left.map(_.toString)

  def toJson(authContext: AuthContext): Json = summon[Encoder[AuthContext]](authContext)

}
