package madrileno.auction.domain

import madrileno.support.TestData
import madrileno.user.domain.UserId
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.time.Instant
import java.util.Currency

class AuctionDomainSpec extends AnyWordSpec with Matchers {

  "Vintage" should {
    "accept valid years" in {
      Vintage.from(1800) shouldBe Right(Vintage(1800))
      Vintage.from(2015) shouldBe Right(Vintage(2015))
      Vintage.from(2100) shouldBe Right(Vintage(2100))
    }

    "reject years outside range" in {
      Vintage.from(1799).isLeft shouldBe true
      Vintage.from(2101).isLeft shouldBe true
    }
  }

  "Price" should {
    "accept positive values" in {
      Price.from(BigDecimal(0.01)) shouldBe Right(Price(BigDecimal(0.01)))
      Price.from(BigDecimal(99999)) shouldBe Right(Price(BigDecimal(99999)))
    }

    "reject zero and negative values" in {
      Price.from(BigDecimal(0)).isLeft shouldBe true
      Price.from(BigDecimal(-1)).isLeft shouldBe true
    }
  }

  "BottleCount" should {
    "accept positive values" in {
      BottleCount.from(1) shouldBe Right(BottleCount(1))
      BottleCount.from(12) shouldBe Right(BottleCount(12))
    }

    "reject zero and negative values" in {
      BottleCount.from(0).isLeft shouldBe true
      BottleCount.from(-1).isLeft shouldBe true
    }
  }

  "WineName" should {
    "accept non-empty strings and trim whitespace" in {
      WineName.from("Château Margaux") shouldBe Right(WineName("Château Margaux"))
      WineName.from("  Trimmed  ") shouldBe Right(WineName("Trimmed"))
    }

    "reject empty and blank strings" in {
      WineName.from("").isLeft shouldBe true
      WineName.from("   ").isLeft shouldBe true
    }
  }

  "Region" should {
    "accept non-empty strings" in {
      Region.from("Bordeaux") shouldBe Right(Region("Bordeaux"))
    }

    "reject empty strings" in {
      Region.from("").isLeft shouldBe true
    }
  }

  "Appellation" should {
    "accept non-empty strings" in {
      Appellation.from("Margaux") shouldBe Right(Appellation("Margaux"))
    }

    "reject empty strings" in {
      Appellation.from("").isLeft shouldBe true
    }
  }

  "ProducerName" should {
    "accept non-empty strings" in {
      ProducerName.from("Jean Foillard") shouldBe Right(ProducerName("Jean Foillard"))
    }

    "reject empty strings" in {
      ProducerName.from("").isLeft shouldBe true
    }
  }

  "Description" should {
    "accept non-empty strings" in {
      Description.from("A fine wine") shouldBe Right(Description("A fine wine"))
    }

    "reject empty strings" in {
      Description.from("").isLeft shouldBe true
    }
  }

  "Auction.placeBid" should {
    val seller = TestData.randomUserId()
    val bidder = TestData.randomUserId()
    val other  = TestData.randomUserId()
    val now    = Instant.now()
    val auction =
      TestData.auction(sellerId = seller, startingPrice = Price(BigDecimal(100)), startsAt = now.minusSeconds(60), endsAt = now.plusSeconds(3600))
    val bidId = TestData.randomBidId()

    def highestBidFrom(userId: UserId, amount: Price): Option[Bid] =
      Some(TestData.bid(auctionId = auction.id, bidderId = userId, amount = amount))

    "produce a Bid tied to this auction on success" in {
      val result = auction.placeBid(bidder, Price(BigDecimal(150)), bidId, now, None)
      result shouldBe Right(Bid(bidId, auction.id, bidder, Price(BigDecimal(150)), now))
    }

    "accept a valid bid above current highest" in {
      auction.placeBid(bidder, Price(BigDecimal(250)), bidId, now, highestBidFrom(other, Price(BigDecimal(200)))) shouldBe
        Right(Bid(bidId, auction.id, bidder, Price(BigDecimal(250)), now))
    }

    "reject bid on non-open auction" in {
      val closed = auction.copy(status = AuctionStatus.Closed)
      closed.placeBid(bidder, Price(BigDecimal(150)), bidId, now, None) shouldBe Left(BidRejection.AuctionNotOpen)
    }

    "reject bid on cancelled auction" in {
      val cancelled = auction.copy(status = AuctionStatus.Cancelled)
      cancelled.placeBid(bidder, Price(BigDecimal(150)), bidId, now, None) shouldBe Left(BidRejection.AuctionNotOpen)
    }

    "reject bid before auction starts" in {
      val notYetStarted = auction.copy(startsAt = now.plusSeconds(60))
      notYetStarted.placeBid(bidder, Price(BigDecimal(150)), bidId, now, None) shouldBe Left(BidRejection.AuctionNotStarted)
    }

    "reject bid at or after endsAt" in {
      val ended = auction.copy(endsAt = now.minusSeconds(1))
      ended.placeBid(bidder, Price(BigDecimal(150)), bidId, now, None) shouldBe Left(BidRejection.AuctionEnded)

      val atBoundary = auction.copy(endsAt = now)
      atBoundary.placeBid(bidder, Price(BigDecimal(150)), bidId, now, None) shouldBe Left(BidRejection.AuctionEnded)
    }

    "reject bid from seller" in {
      auction.placeBid(seller, Price(BigDecimal(150)), bidId, now, None) shouldBe Left(BidRejection.CannotBidOnOwnAuction)
    }

    "reject bid from the current highest bidder" in {
      auction.placeBid(bidder, Price(BigDecimal(250)), bidId, now, highestBidFrom(bidder, Price(BigDecimal(200)))) shouldBe
        Left(BidRejection.AlreadyHighestBidder)
    }

    "reject bid equal to starting price" in {
      auction.placeBid(bidder, Price(BigDecimal(100)), bidId, now, None) shouldBe Left(BidRejection.BidTooLow(Price(BigDecimal(100))))
    }

    "reject bid lower than starting price" in {
      auction.placeBid(bidder, Price(BigDecimal(50)), bidId, now, None) shouldBe Left(BidRejection.BidTooLow(Price(BigDecimal(100))))
    }

    "raise the floor by the minimum increment percentage, accepting a bid exactly at the threshold" in {
      auction.placeBid(bidder, Price(BigDecimal(104)), bidId, now, None, minIncrementPct = 5) shouldBe Left(
        BidRejection.BidTooLow(Price(BigDecimal(105)))
      )
      auction.placeBid(bidder, Price(BigDecimal(105)), bidId, now, None, minIncrementPct = 5) shouldBe a[Right[?, ?]]
      auction.placeBid(bidder, Price(BigDecimal(106)), bidId, now, None, minIncrementPct = 5) shouldBe a[Right[?, ?]]
    }

    "clamp a negative minimum increment to zero" in {
      auction.placeBid(bidder, Price(BigDecimal(150)), bidId, now, None, minIncrementPct = -200) shouldBe a[Right[?, ?]]
    }

    "not overflow on an extreme minimum increment" in {
      auction.placeBid(bidder, Price(BigDecimal(150)), bidId, now, None, minIncrementPct = Int.MaxValue) shouldBe a[Left[?, ?]]
    }

    "reject bid equal to current highest" in {
      auction.placeBid(bidder, Price(BigDecimal(200)), bidId, now, highestBidFrom(other, Price(BigDecimal(200)))) shouldBe Left(
        BidRejection.BidTooLow(Price(BigDecimal(200)))
      )
    }

    "reject bid lower than current highest" in {
      auction.placeBid(bidder, Price(BigDecimal(150)), bidId, now, highestBidFrom(other, Price(BigDecimal(200)))) shouldBe Left(
        BidRejection.BidTooLow(Price(BigDecimal(200)))
      )
    }
  }

  "Auction.open" should {
    val seller = TestData.randomUserId()
    val now    = Instant.now()

    def openAt(startsAt: Instant, endsAt: Instant): Either[AuctionCreationError, Auction] =
      Auction.open(
        id = TestData.randomAuctionId(),
        sellerId = seller,
        wineName = WineName("Test"),
        vintage = Some(Vintage(2020)),
        color = WineColor.Red,
        region = Region("Test"),
        appellation = Appellation("Test"),
        producerName = ProducerName("Test"),
        bottleSize = BottleSize.Standard,
        bottleCount = BottleCount(1),
        description = None,
        startingPrice = Price(BigDecimal(100)),
        currency = Currency.getInstance("EUR"),
        startsAt = startsAt,
        endsAt = endsAt,
        now = now
      )

    "accept a valid window" in {
      openAt(now, now.plusSeconds(3600)).isRight shouldBe true
    }

    "reject endsAt equal to startsAt" in {
      openAt(now, now) shouldBe Left(AuctionCreationError.InvalidWindow)
    }

    "reject endsAt before startsAt" in {
      openAt(now.plusSeconds(60), now) shouldBe Left(AuctionCreationError.InvalidWindow)
    }

    "reject endsAt in the past" in {
      openAt(now.minusSeconds(120), now.minusSeconds(60)) shouldBe Left(AuctionCreationError.InvalidWindow)
    }

    "reject endsAt equal to now" in {
      openAt(now.minusSeconds(60), now) shouldBe Left(AuctionCreationError.InvalidWindow)
    }
  }

  "Auction.cancel" should {
    val seller = TestData.randomUserId()
    val other  = TestData.randomUserId()
    val now    = Instant.now()
    val auction =
      TestData.auction(sellerId = seller, startsAt = now.minusSeconds(60), endsAt = now.plusSeconds(3600), updatedAt = now.minusSeconds(60))

    "cancel an open auction by the seller and bump updatedAt" in {
      val result = auction.cancel(seller, now)
      result.isRight shouldBe true
      val cancelled = result.toOption.get
      cancelled.status shouldBe AuctionStatus.Cancelled
      cancelled.updatedAt shouldBe now
    }

    "reject cancel from non-owner" in {
      auction.cancel(other, now) shouldBe Left(CancellationRejection.NotOwner)
    }

    "reject cancel of closed auction" in {
      val closed = auction.copy(status = AuctionStatus.Closed)
      closed.cancel(seller, now) shouldBe Left(CancellationRejection.AuctionNotOpen)
    }

    "reject cancel of already cancelled auction" in {
      val cancelled = auction.copy(status = AuctionStatus.Cancelled)
      cancelled.cancel(seller, now) shouldBe Left(CancellationRejection.AuctionNotOpen)
    }

    "reject cancel of auction past endsAt" in {
      val ended = auction.copy(endsAt = now.minusSeconds(1))
      ended.cancel(seller, now) shouldBe Left(CancellationRejection.AuctionEnded)

      val atBoundary = auction.copy(endsAt = now)
      atBoundary.cancel(seller, now) shouldBe Left(CancellationRejection.AuctionEnded)
    }
  }

  "Auction.close" should {
    val now     = Instant.now()
    val auction = TestData.auction(updatedAt = now.minusSeconds(60))

    "close an open auction and bump updatedAt" in {
      val result = auction.close(now)
      result.isRight shouldBe true
      val closed = result.toOption.get
      closed.status shouldBe AuctionStatus.Closed
      closed.updatedAt shouldBe now
    }

    "reject close of already closed auction" in {
      val closed = auction.copy(status = AuctionStatus.Closed)
      closed.close(now) shouldBe Left(CloseRejection.AuctionNotOpen)
    }

    "reject close of cancelled auction" in {
      val cancelled = auction.copy(status = AuctionStatus.Cancelled)
      cancelled.close(now) shouldBe Left(CloseRejection.AuctionNotOpen)
    }
  }
}
