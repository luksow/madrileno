package madrileno.auction.repositories

import cats.effect.testing.scalatest.AsyncIOSpec
import madrileno.auction.domain.*
import madrileno.support.{TestData, TestTransactor}
import madrileno.user.repositories.UserRepository
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec

import java.time.Instant

class BidRepositorySpec extends AsyncWordSpec with AsyncIOSpec with Matchers with TestTransactor {

  private lazy val auctionRepo = new AuctionRepository
  private lazy val bidRepo     = new BidRepository
  private lazy val userRepo    = new UserRepository

  private def createAuctionWithUsers() = {
    val seller  = TestData.user()
    val bidder  = TestData.user()
    val auction = TestData.auction(sellerId = seller.id)
    (seller, bidder, auction)
  }

  "BidRepository" should {
    "save and retrieve a bid" in withRollback {
      val (seller, bidder, auction) = createAuctionWithUsers()
      val bid                       = TestData.bid(auctionId = auction.id, bidderId = bidder.id, amount = Price(BigDecimal(200)))
      for {
        _     <- userRepo.create(seller, Instant.now())
        _     <- userRepo.create(bidder, Instant.now())
        _     <- auctionRepo.save(auction)
        saved <- bidRepo.save(bid)
        bids  <- bidRepo.listByAuction(auction.id)
      } yield {
        saved.id shouldBe bid.id
        saved.auctionId shouldBe auction.id
        saved.bidderId shouldBe bidder.id
        saved.amount shouldBe Price(BigDecimal(200))
        bids should have size 1
        bids.head.id shouldBe bid.id
      }
    }

    "return empty list for auction with no bids" in withRollback {
      val (seller, _, auction) = createAuctionWithUsers()
      for {
        _    <- userRepo.create(seller, Instant.now())
        _    <- auctionRepo.save(auction)
        bids <- bidRepo.listByAuction(auction.id)
      } yield bids shouldBe empty
    }

    "highestBid returns the bid with largest amount" in withRollback {
      val (seller, bidder1, auction) = createAuctionWithUsers()
      val bidder2                    = TestData.user()
      val low                        = TestData.bid(auctionId = auction.id, bidderId = bidder1.id, amount = Price(BigDecimal(100)))
      val high                       = TestData.bid(auctionId = auction.id, bidderId = bidder2.id, amount = Price(BigDecimal(500)))
      for {
        _      <- userRepo.create(seller, Instant.now())
        _      <- userRepo.create(bidder1, Instant.now())
        _      <- userRepo.create(bidder2, Instant.now())
        _      <- auctionRepo.save(auction)
        _      <- bidRepo.save(low)
        _      <- bidRepo.save(high)
        result <- bidRepo.highestBid(auction.id)
      } yield {
        result shouldBe defined
        result.get.amount shouldBe Price(BigDecimal(500))
        result.get.bidderId shouldBe bidder2.id
      }
    }

    "highestBid returns None for auction with no bids" in withRollback {
      val (seller, _, auction) = createAuctionWithUsers()
      for {
        _      <- userRepo.create(seller, Instant.now())
        _      <- auctionRepo.save(auction)
        result <- bidRepo.highestBid(auction.id)
      } yield result shouldBe None
    }

    "countByAuction returns correct count" in withRollback {
      val (seller, bidder, auction) = createAuctionWithUsers()
      val bid1                      = TestData.bid(auctionId = auction.id, bidderId = bidder.id, amount = Price(BigDecimal(100)))
      val bid2                      = TestData.bid(auctionId = auction.id, bidderId = bidder.id, amount = Price(BigDecimal(200)))
      for {
        _     <- userRepo.create(seller, Instant.now())
        _     <- userRepo.create(bidder, Instant.now())
        _     <- auctionRepo.save(auction)
        _     <- bidRepo.save(bid1)
        _     <- bidRepo.save(bid2)
        count <- bidRepo.countByAuction(auction.id)
      } yield count shouldBe 2
    }

    "bids are isolated per auction" in withRollback {
      val seller   = TestData.user()
      val bidder   = TestData.user()
      val auction1 = TestData.auction(sellerId = seller.id)
      val auction2 = TestData.auction(sellerId = seller.id)
      val bid1     = TestData.bid(auctionId = auction1.id, bidderId = bidder.id)
      val bid2     = TestData.bid(auctionId = auction2.id, bidderId = bidder.id)
      for {
        _     <- userRepo.create(seller, Instant.now())
        _     <- userRepo.create(bidder, Instant.now())
        _     <- auctionRepo.save(auction1)
        _     <- auctionRepo.save(auction2)
        _     <- bidRepo.save(bid1)
        _     <- bidRepo.save(bid2)
        bids1 <- bidRepo.listByAuction(auction1.id)
        bids2 <- bidRepo.listByAuction(auction2.id)
      } yield {
        bids1 should have size 1
        bids1.head.id shouldBe bid1.id
        bids2 should have size 1
        bids2.head.id shouldBe bid2.id
      }
    }
  }
}
