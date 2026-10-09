package madrileno.auth.domain

import cats.effect.IO
import cats.effect.std.SecureRandom
import com.comcast.ip4s.IpAddress
import madrileno.user.domain.UserId
import madrileno.utils.crypto.{Hmac, SecretBox, Sha256}
import pl.iterators.kebs.opaque.Opaque

import java.nio.charset.StandardCharsets.UTF_8
import java.time.{Duration, Instant}
import java.util.UUID

opaque type RefreshTokenId = UUID
object RefreshTokenId extends Opaque[RefreshTokenId, UUID]

opaque type RefreshTokenFamilyId = UUID
object RefreshTokenFamilyId extends Opaque[RefreshTokenFamilyId, UUID]

final case class RefreshTokenFamily(id: RefreshTokenFamilyId, createdAt: Instant) {
  def olderThan(maxAge: Duration, now: Instant): Boolean = !now.isBefore(createdAt.plus(maxAge))
}

opaque type RefreshTokenSecret = String
object RefreshTokenSecret extends Opaque[RefreshTokenSecret, String] {
  val byteLength: Int = 32

  private val SuccessorKeyLabel = "refresh-token-successor".getBytes(UTF_8)

  extension (secret: RefreshTokenSecret) {
    def hash: RefreshTokenSecretHash = RefreshTokenSecretHash(Sha256.base64Url(secret))

    def sealSuccessor(successor: RefreshTokenSecret)(using SecureRandom[IO]): IO[SealedRefreshTokenSecret] =
      SecretBox.seal(successorKey(secret), successor).map(SealedRefreshTokenSecret.apply)

    def openSuccessor(box: SealedRefreshTokenSecret): Option[RefreshTokenSecret] =
      SecretBox.open(successorKey(secret), box).map(RefreshTokenSecret.apply)
  }

  private def successorKey(secret: RefreshTokenSecret): Array[Byte] = Hmac.sha256(secret.getBytes(UTF_8), SuccessorKeyLabel)
}

opaque type RefreshTokenSecretHash = String
object RefreshTokenSecretHash extends Opaque[RefreshTokenSecretHash, String]

opaque type SealedRefreshTokenSecret = String
object SealedRefreshTokenSecret extends Opaque[SealedRefreshTokenSecret, String]

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
  successor: Option[SealedRefreshTokenSecret],
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

  def rotatedTo(sealedSuccessor: SealedRefreshTokenSecret, instant: Instant): RefreshToken = {
    this.copy(usedAt = Some(instant), successor = Some(sealedSuccessor))
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
      successor = None,
      deletedAt = None,
      expiresAt = now.plus(validFor)
    )
}

final case class IssuedRefreshToken(token: RefreshToken, secret: RefreshTokenSecret)
