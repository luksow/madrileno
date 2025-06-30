package madrileno.auth.domain

import com.comcast.ip4s.IpAddress
import madrileno.user.domain.UserId
import pl.iterators.kebs.opaque.Opaque

import java.util.UUID

opaque type RefreshTokenId = UUID
object RefreshTokenId extends Opaque[RefreshTokenId, UUID]

opaque type UserAgent = String
object UserAgent extends Opaque[UserAgent, String] {
  override def validate(value: String): Either[String, UserAgent] = {
    if (value.trim.nonEmpty) Right(value.trim)
    else Left("Invalid user agent")
  }
}

case class RefreshToken(
  id: RefreshTokenId,
  userId: UserId,
  userAgent: UserAgent,
  ipAddress: IpAddress)
