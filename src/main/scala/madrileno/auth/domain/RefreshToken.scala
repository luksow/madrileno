package madrileno.auth.domain

import com.comcast.ip4s.IpAddress
import madrileno.user.domain.UserId
import madrileno.utils.crypto.Sha256
import pl.iterators.kebs.core.macros.ValueClassLike
import pl.iterators.kebs.opaque.Opaque

import java.time.{Duration, Instant}
import java.util.UUID

opaque type RefreshTokenId = UUID
object RefreshTokenId extends Opaque[RefreshTokenId, UUID]

opaque type RefreshTokenFamilyId = UUID
object RefreshTokenFamilyId extends Opaque[RefreshTokenFamilyId, UUID]

final case class RefreshTokenFamily(id: RefreshTokenFamilyId, createdAt: Instant) {
  def olderThan(maxAge: Duration, now: Instant): Boolean = !now.isBefore(createdAt.plus(maxAge))
}

final case class RefreshTokenSecret private (value: String) {
  def hash: RefreshTokenSecretHash = RefreshTokenSecretHash(Sha256.base64Url(value))

  override def toString: String = "RefreshTokenSecret(redacted)"
}

object RefreshTokenSecret {
  val byteLength: Int = 32

  private val encodedPattern = "[A-Za-z0-9_-]{43}".r

  def from(value: String): Either[String, RefreshTokenSecret] = {
    if (encodedPattern.matches(value)) Right(new RefreshTokenSecret(value))
    else Left("Invalid refresh token")
  }

  def apply(value: String): RefreshTokenSecret = {
    from(value).fold(reason => throw new IllegalArgumentException(reason), identity)
  }

  given ValueClassLike[RefreshTokenSecret, String] = ValueClassLike(apply, _.value)
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
  familyCreatedAt: Instant,
  secretHash: RefreshTokenSecretHash,
  userId: UserId,
  userAgent: UserAgent,
  ipAddress: IpAddress,
  createdAt: Instant,
  usedAt: Option[Instant],
  deletedAt: Option[Instant],
  expiresAt: Instant) {
  def family: RefreshTokenFamily = RefreshTokenFamily(familyId, familyCreatedAt)

  def isValid(now: Instant): Boolean = {
    deletedAt.isEmpty && usedAt.isEmpty && now.isBefore(expiresAt)
  }

  def isUsed: Boolean = usedAt.isDefined

  def isRevoked: Boolean = deletedAt.isDefined

  def wasUsedWithin(grace: Duration, now: Instant): Boolean = {
    usedAt.exists(used => now.isBefore(used.plus(grace)))
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
    family: RefreshTokenFamily,
    secretHash: RefreshTokenSecretHash,
    now: Instant,
    userId: UserId,
    userAgent: UserAgent,
    ipAddress: IpAddress,
    validFor: Duration
  ): RefreshToken =
    RefreshToken(
      id = id,
      familyId = family.id,
      familyCreatedAt = family.createdAt,
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
