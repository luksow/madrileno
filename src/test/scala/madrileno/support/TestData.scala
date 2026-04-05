package madrileno.support

import com.comcast.ip4s.IpAddress
import madrileno.auth.domain.*
import madrileno.user.domain.*
import madrileno.auth.domain.AuthContext
import java.time.Instant
import java.util.UUID

object TestData {
  def randomUserId(): UserId                 = UserId(UUID.randomUUID())
  def randomRefreshTokenId(): RefreshTokenId = RefreshTokenId(UUID.randomUUID())
  def randomUserAuthId(): UserAuthId         = UserAuthId(UUID.randomUUID())

  def authContext(): AuthContext = AuthContext(user = user())

  def user(
    id: UserId = randomUserId(),
    fullName: Option[FullName] = Some(FullName("Test User")),
    emailAddress: Option[EmailAddress] = Some(EmailAddress("test@example.com")),
    emailVerified: Boolean = true,
    avatarUrl: Option[java.net.URI] = None,
    blockedAt: Option[Instant] = None
  ): User = User(id, fullName, emailAddress, emailVerified, avatarUrl, blockedAt)

  def refreshToken(
    id: RefreshTokenId = randomRefreshTokenId(),
    userId: UserId = randomUserId(),
    userAgent: UserAgent = UserAgent("test-agent"),
    ipAddress: IpAddress = IpAddress.fromString("127.0.0.1").get,
    createdAt: Instant = Instant.now(),
    usedAt: Option[Instant] = None,
    deletedAt: Option[Instant] = None
  ): RefreshToken = RefreshToken(id, userId, userAgent, ipAddress, createdAt, usedAt, deletedAt)

  def verifiedExternalToken(
    provider: Provider = Provider.Firebase,
    providerUserId: ProviderUserId = ProviderUserId(s"firebase-${UUID.randomUUID()}"),
    credential: Credential = Credential("test-credential"),
    fullName: Option[FullName] = Some(FullName("Test User")),
    emailAddress: Option[EmailAddress] = Some(EmailAddress("test@example.com")),
    emailVerified: Boolean = true
  ): VerifiedExternalToken = VerifiedExternalToken(
    provider,
    providerUserId,
    credential,
    ExternalProfile(fullName, emailAddress, emailVerified, None),
    Metadata(io.circe.Json.obj())
  )

  val defaultIpAddress: IpAddress = IpAddress.fromString("127.0.0.1").get
}
