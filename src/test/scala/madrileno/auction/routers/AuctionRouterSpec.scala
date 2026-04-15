package madrileno.auction.routers

import cats.effect.IO
import madrileno.auction.domain.*
import madrileno.auction.repositories.{AuctionRepository, BidRepository}
import madrileno.auction.routers.dto.*
import madrileno.auth.domain.AuthContext
import madrileno.support.{BaseRouteSpec, TestApplicationLoader, TestData}
import madrileno.user.domain.{User, UserId}
import madrileno.utils.db.transactor.DB
import madrileno.utils.http.Error
import madrileno.utils.json.JsonProtocol.*
import org.http4s.Method.*
import org.http4s.Status.*
import org.http4s.circe.CirceEntityCodec.*
import pl.iterators.baklava.EmptyBody
import pl.iterators.stir.server.Route

import java.time.Instant
import java.util.{Currency, UUID}

class AuctionRouterSpec extends BaseRouteSpec with TestApplicationLoader {

  override def route: Route = application.routes

  private val eur = Currency.getInstance("EUR")

  private val auctionRepository = new AuctionRepository
  private val bidRepository     = new BidRepository

  private val seller     = TestData.user()
  private val bidder     = TestData.user()
  private val other      = TestData.user()
  private val sellerAuth = AuthContext(seller)
  private val bidderAuth = AuthContext(bidder)
  private val otherAuth  = AuthContext(other)

  // Distinct ids per test (path params are bound at class construction; tests share DB state)
  private val getAuctionFoundId   = AuctionId(UUID.randomUUID())
  private val cancelSuccessId     = AuctionId(UUID.randomUUID())
  private val cancelForbiddenId   = AuctionId(UUID.randomUUID())
  private val cancelNotOpenId     = AuctionId(UUID.randomUUID())
  private val bidSuccessId        = AuctionId(UUID.randomUUID())
  private val bidNotOpenId        = AuctionId(UUID.randomUUID())
  private val bidNotStartedId     = AuctionId(UUID.randomUUID())
  private val bidOwnAuctionId     = AuctionId(UUID.randomUUID())
  private val bidTooLowId         = AuctionId(UUID.randomUUID())
  private val bidAlreadyHighestId = AuctionId(UUID.randomUUID())

  private def seedUser(user: User): DB[User] =
    application.userRepository.find(user.id).flatMap {
      case Some(existing) => IO.pure(existing)
      case None           => application.userRepository.create(user, Instant.now())
    }

  private def seedAuction(
    id: AuctionId,
    sellerId: UserId,
    status: AuctionStatus = AuctionStatus.Open,
    startsAt: Instant = Instant.now().minusSeconds(60),
    endsAt: Instant = Instant.now().plusSeconds(3600)
  ): DB[Auction] = {
    val auction = TestData.auction(id = id, sellerId = sellerId, status = status, startsAt = startsAt, endsAt = endsAt)
    auctionRepository.save(auction).as(auction)
  }

  private def seedBid(
    auctionId: AuctionId,
    bidderId: UserId,
    amount: Price
  ): DB[Bid] = {
    val bid = TestData.bid(auctionId = auctionId, bidderId = bidderId, amount = amount)
    bidRepository.save(bid)
  }

  private def sampleCreateRequest(startsAt: Instant = Instant.now().minusSeconds(60), endsAt: Instant = Instant.now().plusSeconds(3600)) =
    CreateAuctionRequest(
      wineName = WineName("Château Margaux"),
      vintage = Vintage(2015),
      color = WineColor.Red,
      region = Region("Bordeaux"),
      appellation = Appellation("Margaux"),
      producerName = ProducerName("Château Margaux"),
      bottleSize = BottleSize.Standard,
      bottleCount = BottleCount(1),
      description = Some(Description("A fine Bordeaux")),
      startingPrice = Price(BigDecimal(100)),
      currency = eur,
      startsAt = startsAt,
      endsAt = endsAt
    )

  path("/v1/auctions")(
    supports(
      POST,
      description = "Create a new auction",
      summary = "Authenticated seller opens a new wine auction",
      securitySchemes = Seq(bearerScheme),
      tags = Seq("Auctions")
    )(
      onRequest(body = sampleCreateRequest(), security = bearer.apply(validJwt(sellerAuth)))
        .respondsWith[AuctionDto](Created, description = "Auction created")
        .assert { ctx =>
          application.transactor.inSession(seedUser(seller).void).unsafeRunSync()
          val response = ctx.performRequest(allRoutes)
          response.body.sellerId shouldBe seller.id
          response.body.status shouldBe AuctionStatus.Open
          response.body.currentPrice shouldBe Price(BigDecimal(100))
        },
      onRequest(
        body = sampleCreateRequest(startsAt = Instant.now().plusSeconds(60), endsAt = Instant.now()),
        security = bearer.apply(validJwt(sellerAuth))
      ).respondsWith[Error[Unit]](BadRequest, description = "Invalid auction window")
        .assert { ctx =>
          val response = ctx.performRequest(allRoutes)
          response.body.title.exists(_.startsWith("Auction window is invalid")) shouldBe true
        }
    ),
    supports(
      GET,
      description = "List auctions",
      summary = "Unauthenticated: returns auctions optionally filtered by status or seller",
      queryParameters = (q[Option[AuctionStatus]]("status", "Filter by auction status"), q[Option[UserId]]("seller-id", "Filter by seller id")),
      tags = Seq("Auctions")
    )(
      onRequest(queryParameters = (Some(AuctionStatus.Open), None))
        .respondsWith[List[AuctionDto]](Ok, description = "Auctions list")
        .assert { ctx =>
          ctx.performRequest(allRoutes)
        }
    )
  )

  path("/v1/auctions/{auctionId}")(
    supports(
      GET,
      description = "Get an auction by id",
      summary = "Unauthenticated: returns an auction with its current price",
      pathParameters = p[AuctionId]("auctionId"),
      tags = Seq("Auctions")
    )(
      onRequest(pathParameters = getAuctionFoundId)
        .respondsWith[AuctionDto](Ok, description = "Auction found")
        .assert { ctx =>
          application.transactor
            .inSession {
              seedUser(seller) *> seedAuction(getAuctionFoundId, seller.id)
            }
            .void
            .unsafeRunSync()
          val response = ctx.performRequest(allRoutes)
          response.body.id shouldBe getAuctionFoundId
          response.body.sellerId shouldBe seller.id
        },
      onRequest(pathParameters = AuctionId(UUID.randomUUID()))
        .respondsWith[Error[Unit]](NotFound, description = "Auction not found")
        .assert { ctx =>
          val response = ctx.performRequest(allRoutes)
          response.body.title shouldBe Some("Auction not found")
        }
    ),
    supports(
      DELETE,
      description = "Cancel an auction",
      summary = "Seller-only: cancels an open auction",
      securitySchemes = Seq(bearerScheme),
      pathParameters = p[AuctionId]("auctionId"),
      tags = Seq("Auctions")
    )(
      onRequest(security = bearer.apply(validJwt(sellerAuth)), pathParameters = cancelSuccessId)
        .respondsWith[EmptyBody](NoContent, description = "Auction cancelled")
        .assert { ctx =>
          application.transactor
            .inSession {
              seedUser(seller) *> seedAuction(cancelSuccessId, seller.id)
            }
            .void
            .unsafeRunSync()
          ctx.performRequest(allRoutes)
        },
      onRequest(security = bearer.apply(validJwt(sellerAuth)), pathParameters = AuctionId(UUID.randomUUID()))
        .respondsWith[Error[Unit]](NotFound, description = "Auction not found")
        .assert { ctx =>
          val response = ctx.performRequest(allRoutes)
          response.body.title shouldBe Some("Auction not found")
        },
      onRequest(security = bearer.apply(validJwt(otherAuth)), pathParameters = cancelForbiddenId)
        .respondsWith[Error[Unit]](Forbidden, description = "Cancel attempted by non-owner")
        .assert { ctx =>
          application.transactor
            .inSession {
              seedUser(seller) *> seedUser(other) *> seedAuction(cancelForbiddenId, seller.id)
            }
            .void
            .unsafeRunSync()
          val response = ctx.performRequest(allRoutes)
          response.body.title shouldBe Some("Only the seller can cancel this auction")
        },
      onRequest(security = bearer.apply(validJwt(sellerAuth)), pathParameters = cancelNotOpenId)
        .respondsWith[Error[Unit]](Conflict, description = "Auction already cancelled or closed")
        .assert { ctx =>
          application.transactor
            .inSession {
              seedUser(seller) *> seedAuction(cancelNotOpenId, seller.id, status = AuctionStatus.Cancelled)
            }
            .void
            .unsafeRunSync()
          val response = ctx.performRequest(allRoutes)
          response.body.title shouldBe Some("Auction is not open")
        }
    )
  )

  path("/v1/auctions/{auctionId}/bids")(
    supports(
      POST,
      description = "Place a bid on an auction",
      summary = "Authenticated: places a bid above the current highest",
      securitySchemes = Seq(bearerScheme),
      pathParameters = p[AuctionId]("auctionId"),
      tags = Seq("Auctions")
    )(
      onRequest(body = PlaceBidRequest(Price(BigDecimal(150))), security = bearer.apply(validJwt(bidderAuth)), pathParameters = bidSuccessId)
        .respondsWith[BidDto](Created, description = "Bid placed")
        .assert { ctx =>
          application.transactor
            .inSession {
              seedUser(seller) *> seedUser(bidder) *> seedAuction(bidSuccessId, seller.id)
            }
            .void
            .unsafeRunSync()
          val response = ctx.performRequest(allRoutes)
          response.body.amount shouldBe Price(BigDecimal(150))
          response.body.bidderId shouldBe bidder.id
          response.body.auctionId shouldBe bidSuccessId
        },
      onRequest(
        body = PlaceBidRequest(Price(BigDecimal(150))),
        security = bearer.apply(validJwt(bidderAuth)),
        pathParameters = AuctionId(UUID.randomUUID())
      ).respondsWith[Error[Unit]](NotFound, description = "Auction not found")
        .assert { ctx =>
          val response = ctx.performRequest(allRoutes)
          response.body.title shouldBe Some("Auction not found")
        },
      onRequest(body = PlaceBidRequest(Price(BigDecimal(150))), security = bearer.apply(validJwt(bidderAuth)), pathParameters = bidNotOpenId)
        .respondsWith[Error[Unit]](Conflict, description = "Auction is not open")
        .assert { ctx =>
          application.transactor
            .inSession {
              seedUser(seller) *> seedUser(bidder) *> seedAuction(bidNotOpenId, seller.id, status = AuctionStatus.Cancelled)
            }
            .void
            .unsafeRunSync()
          val response = ctx.performRequest(allRoutes)
          response.body.title shouldBe Some("Auction is not open")
        },
      onRequest(body = PlaceBidRequest(Price(BigDecimal(150))), security = bearer.apply(validJwt(bidderAuth)), pathParameters = bidNotStartedId)
        .respondsWith[Error[Unit]](Conflict, description = "Auction has not started yet")
        .assert { ctx =>
          application.transactor
            .inSession {
              seedUser(seller) *> seedUser(bidder) *> seedAuction(
                bidNotStartedId,
                seller.id,
                startsAt = Instant.now().plusSeconds(600),
                endsAt = Instant.now().plusSeconds(3600)
              )
            }
            .void
            .unsafeRunSync()
          val response = ctx.performRequest(allRoutes)
          response.body.title shouldBe Some("Auction has not started yet")
        },
      onRequest(body = PlaceBidRequest(Price(BigDecimal(150))), security = bearer.apply(validJwt(sellerAuth)), pathParameters = bidOwnAuctionId)
        .respondsWith[Error[Unit]](Forbidden, description = "Cannot bid on own auction")
        .assert { ctx =>
          application.transactor
            .inSession {
              seedUser(seller) *> seedAuction(bidOwnAuctionId, seller.id)
            }
            .void
            .unsafeRunSync()
          val response = ctx.performRequest(allRoutes)
          response.body.title shouldBe Some("Cannot bid on your own auction")
        },
      onRequest(body = PlaceBidRequest(Price(BigDecimal(50))), security = bearer.apply(validJwt(bidderAuth)), pathParameters = bidTooLowId)
        .respondsWith[Error[Unit]](Conflict, description = "Bid below minimum")
        .assert { ctx =>
          application.transactor
            .inSession {
              seedUser(seller) *> seedUser(bidder) *> seedAuction(bidTooLowId, seller.id)
            }
            .void
            .unsafeRunSync()
          val response = ctx.performRequest(allRoutes)
          response.body.title.exists(_.startsWith("Bid must be strictly greater than")) shouldBe true
        },
      onRequest(body = PlaceBidRequest(Price(BigDecimal(250))), security = bearer.apply(validJwt(bidderAuth)), pathParameters = bidAlreadyHighestId)
        .respondsWith[Error[Unit]](Conflict, description = "Already the highest bidder")
        .assert { ctx =>
          application.transactor
            .inSession {
              seedUser(seller) *> seedUser(bidder) *>
                seedAuction(bidAlreadyHighestId, seller.id) *>
                seedBid(bidAlreadyHighestId, bidder.id, Price(BigDecimal(200)))
            }
            .void
            .unsafeRunSync()
          val response = ctx.performRequest(allRoutes)
          response.body.title shouldBe Some("You already have the highest bid")
        }
    )
  )
}
