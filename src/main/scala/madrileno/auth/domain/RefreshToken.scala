package madrileno.auth.domain

import cats.effect.IO
import com.comcast.ip4s.IpAddress
import madrileno.user.domain.UserId
import madrileno.utils.crypto.{RandomSecret, Sha256}
import pl.iterators.kebs.opaque.Opaque

import java.time.{Duration, Instant}
import java.util.UUID

opaque type RefreshTokenId = UUID
object RefreshTokenId extends Opaque[RefreshTokenId, UUID]

opaque type RefreshTokenFamilyId = UUID
object RefreshTokenFamilyId extends Opaque[RefreshTokenFamilyId, UUID]

opaque type RefreshTokenSecret = String
object RefreshTokenSecret extends Opaque[RefreshTokenSecret, String] {
  private val secretBytes = 32

  override def validate(value: String): Either[String, RefreshTokenSecret] = {
    if (value.nonEmpty) Right(value)
    else Left("Invalid refresh token")
  }

  def generate: IO[RefreshTokenSecret] = RandomSecret.generate(secretBytes).map(RefreshTokenSecret.apply)

  extension (secret: RefreshTokenSecret) {
    def hash: RefreshTokenSecretHash = RefreshTokenSecretHash(Sha256.base64Url(secret))
  }
}

opaque type RefreshTokenSecretHash = String
object RefreshTokenSecretHash extends Opaque[RefreshTokenSecretHash, String]

opaque type UserAgent = String
object UserAgent extends Opaque[UserAgent, String] {
  override def validate(value: String): Either[String, UserAgent] = {
    if (value.trim.nonEmpty) Right(value.trim)
    else Left("Invalid user agent")
  }
}

final case class RefreshToken(
  id: RefreshTokenId,
  familyId: RefreshTokenFamilyId,
  secretHash: RefreshTokenSecretHash,
  userId: UserId,
  userAgent: UserAgent,
  ipAddress: IpAddress,
  createdAt: Instant,
  usedAt: Option[Instant],
  deletedAt: Option[Instant],
  expiresAt: Instant) {
  def isValid(now: Instant): Boolean = {
    deletedAt.isEmpty && usedAt.isEmpty && now.isBefore(expiresAt)
  }

  def isUsed: Boolean = usedAt.isDefined

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
    familyId: RefreshTokenFamilyId,
    secretHash: RefreshTokenSecretHash,
    now: Instant,
    userId: UserId,
    userAgent: UserAgent,
    ipAddress: IpAddress,
    validFor: Duration
  ): RefreshToken =
    RefreshToken(
      id = id,
      familyId = familyId,
      secretHash = secretHash,
      userId = userId,
      userAgent = userAgent,
      ipAddress = ipAddress,
      createdAt = now,
      usedAt = None,
      deletedAt = None,
      expiresAt = now.plus(validFor)
    )
}

final case class IssuedRefreshToken(token: RefreshToken, secret: RefreshTokenSecret)
