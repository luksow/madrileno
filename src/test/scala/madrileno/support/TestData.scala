package madrileno.support

import com.comcast.ip4s.IpAddress
import madrileno.auction.domain.*
import madrileno.auth.domain.{AuthContext, *}
import madrileno.user.domain.*

import java.time.Instant
import java.util.{Currency, UUID}

object TestData {
  def randomUserId(): UserId                 = UserId(UUID.randomUUID())
  def randomRefreshTokenId(): RefreshTokenId = RefreshTokenId(UUID.randomUUID())
  val knownRefreshTokenId: RefreshTokenId    = RefreshTokenId(UUID.fromString("00000000-0000-0000-0000-000000000001"))
  def randomUserAuthId(): UserAuthId         = UserAuthId(UUID.randomUUID())
  def randomAuctionId(): AuctionId           = AuctionId(UUID.randomUUID())
  def randomBidId(): BidId                   = BidId(UUID.randomUUID())

  def authContext(): AuthContext = AuthContext(user = user())

  def user(
    id: UserId = randomUserId(),
    fullName: Option[FullName] = Some(FullName("Test User")),
    emailAddress: Option[EmailAddress] = Some(EmailAddress(s"test-${UUID.randomUUID()}@example.com")),
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
    emailAddress: Option[EmailAddress] = Some(EmailAddress(s"test-${UUID.randomUUID()}@example.com")),
    emailVerified: Boolean = true
  ): VerifiedExternalToken = VerifiedExternalToken(
    provider,
    providerUserId,
    credential,
    ExternalProfile(fullName, emailAddress, emailVerified, None),
    Metadata(io.circe.Json.obj())
  )

  val defaultIpAddress: IpAddress = IpAddress.fromString("127.0.0.1").get

  def auction(
    id: AuctionId = randomAuctionId(),
    sellerId: UserId = randomUserId(),
    wineName: WineName = WineName("Château Margaux"),
    vintage: Option[Vintage] = Some(Vintage(2015)),
    color: WineColor = WineColor.Red,
    region: Region = Region("Bordeaux"),
    appellation: Appellation = Appellation("Margaux"),
    producerName: ProducerName = ProducerName("Château Margaux"),
    bottleSize: BottleSize = BottleSize.Standard,
    bottleCount: BottleCount = BottleCount(1),
    description: Option[Description] = Some(Description("A fine wine")),
    startingPrice: Price = Price(BigDecimal(100)),
    currency: Currency = Currency.getInstance("EUR"),
    status: AuctionStatus = AuctionStatus.Open,
    startsAt: Instant = Instant.now(),
    endsAt: Instant = Instant.now().plusSeconds(3600),
    createdAt: Instant = Instant.now(),
    updatedAt: Instant = Instant.now(),
    deletedAt: Option[Instant] = None
  ): Auction = Auction(
    id,
    sellerId,
    wineName,
    vintage,
    color,
    region,
    appellation,
    producerName,
    bottleSize,
    bottleCount,
    description,
    startingPrice,
    currency,
    status,
    startsAt,
    endsAt,
    createdAt,
    updatedAt,
    deletedAt
  )

  def bid(
    id: BidId = randomBidId(),
    auctionId: AuctionId = randomAuctionId(),
    bidderId: UserId = randomUserId(),
    amount: Price = Price(BigDecimal(150)),
    createdAt: Instant = Instant.now()
  ): Bid = Bid(id, auctionId, bidderId, amount, createdAt)
}
