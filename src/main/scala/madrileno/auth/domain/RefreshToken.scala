package madrileno.auth.domain

import com.comcast.ip4s.IpAddress
import madrileno.user.domain.UserId
import pl.iterators.kebs.opaque.Opaque

import java.time.Instant
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

final case class RefreshToken(
  id: RefreshTokenId,
  userId: UserId,
  userAgent: UserAgent,
  ipAddress: IpAddress,
  createdAt: Instant,
  usedAt: Option[Instant],
  deletedAt: Option[Instant]) {
  def isValid: Boolean = {
    deletedAt.isEmpty && usedAt.isEmpty
  }

  def usedAt(instant: Instant): RefreshToken = {
    this.copy(usedAt = Some(instant))
  }

  def deletedAt(instant: Instant): RefreshToken = {
    this.copy(deletedAt = Some(instant))
  }
}

object RefreshToken {
  def mint(
    id: RefreshTokenId,
    now: Instant,
    userId: UserId,
    userAgent: UserAgent,
    ipAddress: IpAddress
  ): RefreshToken =
    RefreshToken(id, userId, userAgent, ipAddress, createdAt = now, usedAt = None, deletedAt = None)
}
