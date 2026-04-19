package madrileno.auction.services

import cats.effect.std.UUIDGen
import cats.effect.testing.scalatest.AsyncIOSpec
import cats.effect.{Clock, IO}
import io.opentelemetry.api.OpenTelemetry
import madrileno.auction.domain.*
import madrileno.auction.gateways.VivinoGateway
import madrileno.auction.repositories.{AuctionRepository, BidRepository}
import madrileno.support.{TestData, TestGivens, TestMailpit, TestTransactor}
import madrileno.utils.events.{EventBus, EventBusRuntime}
import madrileno.user.domain.{User, UserId}
import madrileno.user.repositories.UserRepository
import madrileno.utils.mailer.*
import madrileno.utils.observability.TelemetryContext
import madrileno.utils.task.*
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import org.typelevel.otel4s.metrics.Meter
import org.typelevel.otel4s.trace.Tracer

import java.net.URI
import java.time.Instant
import java.util.Currency
import scala.concurrent.duration.*

class AuctionServiceSpec extends AsyncWordSpec with AsyncIOSpec with Matchers with TestTransactor with TestMailpit {

  private val testClock   = TestGivens.fixedClock()
  private val testUUIDGen = TestGivens.deterministicUUIDs()
  given Clock[IO]         = testClock
  given UUIDGen[IO]       = testUUIDGen
  given TelemetryContext  = TelemetryContext(Meter.noop[IO], Tracer.noop[IO], OpenTelemetry.noop())

  private lazy val userRepo    = new UserRepository
  private lazy val auctionRepo = new AuctionRepository
  private lazy val bidRepo     = new BidRepository

  private lazy val smtpSender = SmtpSender(MailerConfig(host = mailpitHost, port = mailpitSmtpPort, fromAddress = "test@example.com", tls = false))
  private lazy val scheduler  = Scheduler(transactor, SchedulerConfig(pollingInterval = 1.second))
  private lazy val client     = scheduler.client
  private lazy val mailer     = new Mailer(smtpSender, client, MailContext(baseUrl = URI("https://example.com")))
  private val vivinoGateway: VivinoGateway     = (_, _) => IO.pure(None)
  private val eventBus: EventBus[AuctionEvent] = EventBusRuntime.local.topic[AuctionEvent]("auction.events.test")
  private lazy val service                     = new AuctionService(auctionRepo, bidRepo, userRepo, vivinoGateway, eventBus, transactor, mailer)

  private val eur = Currency.getInstance("EUR")

  // Service manages its own sessions/transactions, so we seed data via the transactor directly
  private def seedUser(user: User = TestData.user()): IO[User] = {
    transactor.inSession { userRepo.create(user, Instant.now()) }
  }

  // Convenience for tests that expect creation to succeed
  private def createAuctionOrFail(command: CreateAuctionCommand): IO[AuctionView] = {
    service.createAuction(command).map {
      case CreateAuctionResult.Created(view) => view
      case other                             => fail(s"Expected Created, got $other")
    }
  }

  // Seed an auction in an arbitrary state (past endsAt, etc.) directly through the repository,
  // bypassing Auction.open domain guards. Needed to set up closeExpiredAuctionsTask scenarios.
  private def seedAuction(auction: Auction): IO[Auction] = {
    transactor.inSession { auctionRepo.save(auction).as(auction) }
  }

  // Use testClock.now so temporal relationships stay consistent with what the service observes via Clock[IO]
  private def createCommand(
    sellerId: UserId,
    startsAt: Instant = testClock.now.minusSeconds(60),
    endsAt: Instant = testClock.now.plusSeconds(3600)
  ) = CreateAuctionCommand(
    sellerId = sellerId,
    wineName = WineName("Château Margaux"),
    vintage = Some(Vintage(2015)),
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

  "AuctionService" should {
    "create an auction" in {
      for {
        seller  <- seedUser()
        auction <- createAuctionOrFail(createCommand(seller.id))
      } yield {
        auction.sellerId shouldBe seller.id
        auction.wineName shouldBe WineName("Château Margaux")
        auction.status shouldBe AuctionStatus.Open
        auction.currentPrice shouldBe Price(BigDecimal(100))
        auction.currency shouldBe eur
      }
    }

    "get an auction by id" in {
      for {
        seller  <- seedUser()
        created <- createAuctionOrFail(createCommand(seller.id))
        found   <- service.getAuction(created.id)
      } yield {
        found shouldBe defined
        found.get.id shouldBe created.id
      }
    }

    "return None for non-existent auction" in {
      service.getAuction(TestData.randomAuctionId()).map(_ shouldBe None)
    }

    "get an NV auction (vintage = None) round-trip" in {
      for {
        seller  <- seedUser()
        created <- createAuctionOrFail(createCommand(seller.id).copy(vintage = None))
        found   <- service.getAuction(created.id)
      } yield {
        found shouldBe defined
        found.get.vintage shouldBe None
      }
    }

    "populate the auction with a rating when the gateway returns one" in {
      val ratedGateway: VivinoGateway = (_, _) => IO.pure(Some(VivinoRating(Rating(BigDecimal(4.7)), RatingsCount(12345))))
      val ratedService                = new AuctionService(auctionRepo, bidRepo, userRepo, ratedGateway, eventBus, transactor, mailer)
      for {
        seller  <- seedUser()
        created <- createAuctionOrFail(createCommand(seller.id))
        found   <- ratedService.getAuction(created.id)
      } yield {
        found.flatMap(_.rating).map(_.rating.unwrap) shouldBe Some(BigDecimal(4.7))
        found.flatMap(_.rating).map(_.ratingsCount.unwrap) shouldBe Some(12345)
      }
    }

    "list auctions with filter" in {
      for {
        seller <- seedUser()
        a1     <- createAuctionOrFail(createCommand(seller.id))
        _      <- service.cancelAuction(CancelAuctionCommand(a1.id, seller.id))
        a2     <- createAuctionOrFail(createCommand(seller.id))
        open   <- service.listAuctions(ListAuctionsFilter(status = Some(AuctionStatus.Open)))
      } yield {
        open.map(_.id) should contain(a2.id)
      }
    }

    "place a bid on an open auction" in {
      for {
        seller  <- seedUser()
        bidder  <- seedUser()
        auction <- createAuctionOrFail(createCommand(seller.id))
        result  <- service.placeBid(PlaceBidCommand(auction.id, bidder.id, Price(BigDecimal(150))))
      } yield {
        result shouldBe a[PlaceBidResult.BidPlaced]
        val bid = result.asInstanceOf[PlaceBidResult.BidPlaced].bid // scalafix:ok DisableSyntax.asInstanceOf
        bid.amount shouldBe Price(BigDecimal(150))
        bid.bidderId shouldBe bidder.id
      }
    }

    "reject bid lower than starting price" in {
      for {
        seller  <- seedUser()
        bidder  <- seedUser()
        auction <- createAuctionOrFail(createCommand(seller.id))
        result  <- service.placeBid(PlaceBidCommand(auction.id, bidder.id, Price(BigDecimal(50))))
      } yield result shouldBe a[PlaceBidResult.BidTooLow]
    }

    "reject bid equal to current highest" in {
      for {
        seller  <- seedUser()
        bidder1 <- seedUser()
        bidder2 <- seedUser()
        auction <- createAuctionOrFail(createCommand(seller.id))
        _       <- service.placeBid(PlaceBidCommand(auction.id, bidder1.id, Price(BigDecimal(200))))
        result  <- service.placeBid(PlaceBidCommand(auction.id, bidder2.id, Price(BigDecimal(200))))
      } yield result shouldBe a[PlaceBidResult.BidTooLow]
    }

    "reject bid on non-existent auction" in {
      for {
        bidder <- seedUser()
        result <- service.placeBid(PlaceBidCommand(TestData.randomAuctionId(), bidder.id, Price(BigDecimal(150))))
      } yield result shouldBe PlaceBidResult.AuctionNotFound
    }

    "reject bid on cancelled auction" in {
      for {
        seller  <- seedUser()
        bidder  <- seedUser()
        auction <- createAuctionOrFail(createCommand(seller.id))
        _       <- service.cancelAuction(CancelAuctionCommand(auction.id, seller.id))
        result  <- service.placeBid(PlaceBidCommand(auction.id, bidder.id, Price(BigDecimal(150))))
      } yield result shouldBe PlaceBidResult.AuctionNotOpen
    }

    "reject bid on own auction" in {
      for {
        seller  <- seedUser()
        auction <- createAuctionOrFail(createCommand(seller.id))
        result  <- service.placeBid(PlaceBidCommand(auction.id, seller.id, Price(BigDecimal(150))))
      } yield result shouldBe PlaceBidResult.CannotBidOnOwnAuction
    }

    "reject bid before auction starts" in {
      for {
        seller <- seedUser()
        bidder <- seedUser()
        cmd = createCommand(seller.id).copy(startsAt = testClock.now.plusSeconds(600))
        auction <- createAuctionOrFail(cmd)
        result  <- service.placeBid(PlaceBidCommand(auction.id, bidder.id, Price(BigDecimal(150))))
      } yield result shouldBe PlaceBidResult.AuctionNotStarted
    }

    "reject second bid from the current highest bidder" in {
      for {
        seller  <- seedUser()
        bidder  <- seedUser()
        auction <- createAuctionOrFail(createCommand(seller.id))
        _       <- service.placeBid(PlaceBidCommand(auction.id, bidder.id, Price(BigDecimal(150))))
        result  <- service.placeBid(PlaceBidCommand(auction.id, bidder.id, Price(BigDecimal(200))))
      } yield result shouldBe PlaceBidResult.AlreadyHighestBidder
    }

    "reject auction creation with invalid window (endsAt <= startsAt)" in {
      for {
        seller <- seedUser()
        now = testClock.now
        cmd = createCommand(seller.id).copy(startsAt = now.plusSeconds(10), endsAt = now.plusSeconds(10))
        result <- service.createAuction(cmd)
      } yield result shouldBe CreateAuctionResult.InvalidWindow
    }

    "send outbid email to the previous highest bidder" in {
      scheduler
        .run(oneTimeTasks = List(mailer.sendMailTask))
        .use { _ =>
          for {
            _       <- clearMailpit()
            seller  <- seedUser()
            bidder1 <- seedUser()
            bidder2 <- seedUser()
            bidder1Email = bidder1.emailAddress.get.toString
            auction <- createAuctionOrFail(createCommand(seller.id))
            _       <- service.placeBid(PlaceBidCommand(auction.id, bidder1.id, Price(BigDecimal(150))))
            _       <- service.placeBid(PlaceBidCommand(auction.id, bidder2.id, Price(BigDecimal(250))))
            mails   <- waitForMail(_.exists(m => m.to.exists(_.address == bidder1Email) && m.subject.contains("outbid")))
          } yield (mails, bidder1Email)
        }
        .map { case (messages, bidder1Email) =>
          val forBidder1 = messages.filter(_.to.exists(_.address == bidder1Email))
          forBidder1.size shouldBe 1
          forBidder1.head.subject should (include("outbid") and include("Château Margaux"))
        }
    }

    "update current price after bid" in {
      for {
        seller  <- seedUser()
        bidder  <- seedUser()
        auction <- createAuctionOrFail(createCommand(seller.id))
        _       <- service.placeBid(PlaceBidCommand(auction.id, bidder.id, Price(BigDecimal(250))))
        found   <- service.getAuction(auction.id)
      } yield {
        found shouldBe defined
        found.get.currentPrice shouldBe Price(BigDecimal(250))
      }
    }

    "cancel an auction" in {
      for {
        seller  <- seedUser()
        auction <- createAuctionOrFail(createCommand(seller.id))
        result  <- service.cancelAuction(CancelAuctionCommand(auction.id, seller.id))
        found   <- service.getAuction(auction.id)
      } yield {
        result shouldBe CancelAuctionResult.Cancelled
        found shouldBe defined
        found.get.status shouldBe AuctionStatus.Cancelled
      }
    }

    "reject cancel from non-owner" in {
      for {
        seller  <- seedUser()
        other   <- seedUser()
        auction <- createAuctionOrFail(createCommand(seller.id))
        result  <- service.cancelAuction(CancelAuctionCommand(auction.id, other.id))
      } yield result shouldBe CancelAuctionResult.NotOwner
    }

    "reject cancel of already cancelled auction" in {
      for {
        seller  <- seedUser()
        auction <- createAuctionOrFail(createCommand(seller.id))
        _       <- service.cancelAuction(CancelAuctionCommand(auction.id, seller.id))
        result  <- service.cancelAuction(CancelAuctionCommand(auction.id, seller.id))
      } yield result shouldBe CancelAuctionResult.AuctionNotOpen
    }

    "reject cancel of auction that has already ended (before closer catches up)" in {
      for {
        seller <- seedUser()
        past = testClock.now.minusSeconds(60)
        auction <- seedAuction(TestData.auction(sellerId = seller.id, startsAt = past.minusSeconds(120), endsAt = past))
        result  <- service.cancelAuction(CancelAuctionCommand(auction.id, seller.id))
      } yield result shouldBe CancelAuctionResult.AuctionEnded
    }

    "reject bid on auction that has already ended (before closer catches up)" in {
      for {
        seller <- seedUser()
        bidder <- seedUser()
        past = testClock.now.minusSeconds(60)
        auction <- seedAuction(TestData.auction(sellerId = seller.id, startsAt = past.minusSeconds(120), endsAt = past))
        result  <- service.placeBid(PlaceBidCommand(auction.id, bidder.id, Price(BigDecimal(150))))
      } yield result shouldBe PlaceBidResult.AuctionEnded
    }
  }

  "closeExpiredAuctionsTask" should {
    lazy val task = service.closeExpiredAuctionsTask

    "close an auction past its endsAt" in {
      for {
        seller <- seedUser()
        past = testClock.now.minusSeconds(60)
        auction <- seedAuction(TestData.auction(sellerId = seller.id, startsAt = past.minusSeconds(120), endsAt = past))
        _       <- task.execution(task)
        found   <- service.getAuction(auction.id)
      } yield {
        found shouldBe defined
        found.get.status shouldBe AuctionStatus.Closed
      }
    }

    "leave non-expired auctions open" in {
      for {
        seller <- seedUser()
        future = testClock.now.plusSeconds(3600)
        auction <- createAuctionOrFail(createCommand(seller.id, endsAt = future))
        _       <- task.execution(task)
        found   <- service.getAuction(auction.id)
      } yield {
        found shouldBe defined
        found.get.status shouldBe AuctionStatus.Open
      }
    }

    "notify seller and winner when auction with bids closes" in {
      scheduler
        .run(oneTimeTasks = List(mailer.sendMailTask))
        .use { _ =>
          for {
            _      <- clearMailpit()
            seller <- seedUser()
            winner <- seedUser()
            sellerEmail = seller.emailAddress.get.toString
            winnerEmail = winner.emailAddress.get.toString
            past        = testClock.now.minusSeconds(60)
            auction <- seedAuction(TestData.auction(sellerId = seller.id, startsAt = past.minusSeconds(120), endsAt = past))
            _ <- transactor.inSession { bidRepo.save(TestData.bid(auctionId = auction.id, bidderId = winner.id, amount = Price(BigDecimal(200)))) }
            _ <- task.execution(task)
            mails <- waitForMail { ms =>
                       ms.exists(_.to.exists(_.address == sellerEmail)) && ms.exists(_.to.exists(_.address == winnerEmail))
                     }
          } yield (mails, sellerEmail, winnerEmail)
        }
        .map { case (messages, sellerEmail, winnerEmail) =>
          val forSeller = messages.filter(_.to.exists(_.address == sellerEmail))
          val forWinner = messages.filter(_.to.exists(_.address == winnerEmail))
          forSeller.size shouldBe 1
          forSeller.head.subject should include("Auction closed")
          forWinner.size shouldBe 1
          forWinner.head.subject should include("Auction closed")
        }
    }

    "notify only seller when auction with no bids closes" in {
      scheduler
        .run(oneTimeTasks = List(mailer.sendMailTask))
        .use { _ =>
          for {
            _      <- clearMailpit()
            seller <- seedUser()
            sellerEmail = seller.emailAddress.get.toString
            past        = testClock.now.minusSeconds(60)
            auction <- seedAuction(TestData.auction(sellerId = seller.id, startsAt = past.minusSeconds(120), endsAt = past))
            _       <- task.execution(task)
            mails   <- waitForMail(_.exists(m => m.to.exists(_.address == sellerEmail)))
            _       <- service.getAuction(auction.id)
          } yield (mails, sellerEmail)
        }
        .map { case (messages, sellerEmail) =>
          val forThisSeller = messages.filter(_.to.exists(_.address == sellerEmail))
          forThisSeller.size shouldBe 1
          forThisSeller.head.subject should include("Auction closed")
        }
    }

    "be idempotent — second run on already-closed auction is a no-op" in {
      for {
        seller <- seedUser()
        past = testClock.now.minusSeconds(60)
        auction <- seedAuction(TestData.auction(sellerId = seller.id, startsAt = past.minusSeconds(120), endsAt = past))
        _       <- task.execution(task)
        first   <- service.getAuction(auction.id)
        _       <- task.execution(task)
        second  <- service.getAuction(auction.id)
      } yield {
        first.get.status shouldBe AuctionStatus.Closed
        second.get.status shouldBe AuctionStatus.Closed
      }
    }
  }
}
