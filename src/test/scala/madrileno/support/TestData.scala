package madrileno.support

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import com.comcast.ip4s.IpAddress
import io.circe.Json
import madrileno.auction.domain.*
import madrileno.auth.domain.{AuthContext, *}
import madrileno.user.domain.*
import madrileno.utils.crypto.UuidV7
import madrileno.utils.imaging.{Height, ImageFormat, Width}
import madrileno.utils.storage.StorageKey
import org.http4s.MediaType
import org.http4s.headers.`Content-Type`

import java.net.URI
import java.time.Instant
import java.util.{Currency, UUID}

object TestData {
  // Mint via the same time-ordered UUIDv7 path as production (IdGenerator), so fixtures mirror real ids — never raw v4.
  def randomUuid(): UUID = UuidV7.generate.unsafeRunSync()

  def randomUserId(): UserId                 = UserId(randomUuid())
  def randomRefreshTokenId(): RefreshTokenId = RefreshTokenId(randomUuid())
  def randomUserAuthId(): UserAuthId         = UserAuthId(randomUuid())
  // scripts:auction-block-start
  def randomAuctionId(): AuctionId                         = AuctionId(randomUuid())
  def randomBidId(): BidId                                 = BidId(randomUuid())
  def randomAuctionImageId(): AuctionImageId               = AuctionImageId(randomUuid())
  def randomAuctionImageVariantId(): AuctionImageVariantId = AuctionImageVariantId(randomUuid())
  // scripts:auction-block-end

  def authContext(): AuthContext = AuthContext(user = user())

  def user(
    id: UserId = randomUserId(),
    fullName: Option[FullName] = Some(FullName("Test User")),
    emailAddress: Option[EmailAddress] = Some(EmailAddress(s"test-${randomUuid()}@example.com")),
    emailVerified: Boolean = true,
    avatarUrl: Option[URI] = None,
    blockedAt: Option[Instant] = None
  ): User = User(id, fullName, emailAddress, emailVerified, avatarUrl, blockedAt)

  def refreshToken(
    id: RefreshTokenId = randomRefreshTokenId(),
    userId: UserId = randomUserId(),
    userAgent: UserAgent = UserAgent("test-agent"),
    ipAddress: IpAddress = IpAddress.fromString("127.0.0.1").get,
    createdAt: Instant = Instant.now(),
    usedAt: Option[Instant] = None,
    deletedAt: Option[Instant] = None,
    expiresAt: Option[Instant] = None
  ): RefreshToken = RefreshToken(id, userId, userAgent, ipAddress, createdAt, usedAt, deletedAt, expiresAt)

  def verifiedExternalToken(
    provider: Provider = Provider.Firebase,
    providerUserId: ProviderUserId = ProviderUserId(s"firebase-${randomUuid()}"),
    credential: Credential = Credential("test-credential"),
    fullName: Option[FullName] = Some(FullName("Test User")),
    emailAddress: Option[EmailAddress] = Some(EmailAddress(s"test-${randomUuid()}@example.com")),
    emailVerified: Boolean = true
  ): VerifiedExternalToken =
    VerifiedExternalToken(provider, providerUserId, credential, ExternalProfile(fullName, emailAddress, emailVerified, None), Metadata(Json.obj()))

  val defaultIpAddress: IpAddress = IpAddress.fromString("127.0.0.1").get

  // scripts:auction-block-start
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

  def auctionImage(
    id: AuctionImageId = randomAuctionImageId(),
    auctionId: AuctionId = randomAuctionId(),
    storageKey: StorageKey = StorageKey(s"auctions/test/images/${randomUuid()}"),
    fileName: String = "wine.jpg",
    contentType: `Content-Type` = `Content-Type`(MediaType.image.jpeg),
    sizeBytes: SizeBytes = SizeBytes(1024L),
    position: ImagePosition = ImagePosition(0),
    uploadedAt: Instant = Instant.now(),
    deletedAt: Option[Instant] = None,
    width: Option[Width] = None,
    height: Option[Height] = None,
    format: Option[ImageFormat] = None,
    analyzedAt: Option[Instant] = None
  ): AuctionImage =
    AuctionImage(id, auctionId, storageKey, fileName, contentType, sizeBytes, position, uploadedAt, deletedAt, width, height, format, analyzedAt)
  // scripts:auction-block-end
}
