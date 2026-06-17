package madrileno.auction.repositories

import cats.effect.testing.scalatest.AsyncIOSpec
import madrileno.auction.domain.*
import madrileno.support.{TestData, TestTransactor}
import madrileno.user.repositories.UserRepository
import madrileno.utils.db.dsl.*
import madrileno.utils.pagination.{CursorRequest, Limit, SortDirection}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec

import java.time.Instant

class BidRepositorySpec extends AsyncWordSpec with AsyncIOSpec with Matchers with TestTransactor {

  private lazy val auctionRepo = new AuctionRepository
  private lazy val bidRepo     = new BidRepository
  private lazy val userRepo    = new UserRepository

  private lazy val bidFilteringRepo: FilteringRepository[BidRow, BidRowFilter] =
    new FilteringRepository[BidRow, BidRowFilter] { override val table: BidRowTable.type = BidRowTable }

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

    "pageByAuction returns only this auction's bids, newest-first by id, with a working cursor" in withRollback {
      val seller   = TestData.user()
      val bidder   = TestData.user()
      val auction  = TestData.auction(sellerId = seller.id)
      val other    = TestData.auction(sellerId = seller.id)
      val b1       = TestData.bid(auctionId = auction.id, bidderId = bidder.id, createdAt = Instant.parse("2026-01-01T00:00:01Z"))
      val b2       = TestData.bid(auctionId = auction.id, bidderId = bidder.id, createdAt = Instant.parse("2026-01-01T00:00:02Z"))
      val b3       = TestData.bid(auctionId = auction.id, bidderId = bidder.id, createdAt = Instant.parse("2026-01-01T00:00:03Z"))
      val otherBid = TestData.bid(auctionId = other.id, bidderId = bidder.id, createdAt = Instant.parse("2026-01-01T00:00:04Z"))
      for {
        _     <- userRepo.create(seller, Instant.now())
        _     <- userRepo.create(bidder, Instant.now())
        _     <- auctionRepo.save(auction)
        _     <- auctionRepo.save(other)
        _     <- bidRepo.save(b1)
        _     <- bidRepo.save(b2)
        _     <- bidRepo.save(b3)
        _     <- bidRepo.save(otherBid)
        page1 <- bidRepo.pageByAuction(auction.id, CursorRequest(Limit(2), None))
        page2 <- bidRepo.pageByAuction(auction.id, CursorRequest(Limit(2), Some(page1.items.last.id)))
      } yield {
        page1.items.map(_.id) shouldBe List(b3.id, b2.id)
        page1.hasMore shouldBe true
        page2.items.map(_.id) shouldBe List(b1.id)
        page2.hasMore shouldBe false
        (page1.items ++ page2.items).map(_.id) should not contain otherBid.id
      }
    }

    "findCursorPageByKey pages ascending by key when direction is Asc" in withRollback {
      val seller  = TestData.user()
      val bidder  = TestData.user()
      val auction = TestData.auction(sellerId = seller.id)
      val b1      = TestData.bid(auctionId = auction.id, bidderId = bidder.id, createdAt = Instant.parse("2026-01-01T00:00:01Z"))
      val b2      = TestData.bid(auctionId = auction.id, bidderId = bidder.id, createdAt = Instant.parse("2026-01-01T00:00:02Z"))
      val b3      = TestData.bid(auctionId = auction.id, bidderId = bidder.id, createdAt = Instant.parse("2026-01-01T00:00:03Z"))
      val filter  = BidRowFilter(auctionId = p.equal(auction.id))
      for {
        _     <- userRepo.create(seller, Instant.now())
        _     <- userRepo.create(bidder, Instant.now())
        _     <- auctionRepo.save(auction)
        _     <- bidRepo.save(b1)
        _     <- bidRepo.save(b2)
        _     <- bidRepo.save(b3)
        page1 <- bidFilteringRepo.findCursorPageByKey(filter, BidRowTable.id, CursorRequest(Limit(2), None), SortDirection.Asc)
        page2 <- bidFilteringRepo.findCursorPageByKey(filter, BidRowTable.id, CursorRequest(Limit(2), Some(page1._1.last.id)), SortDirection.Asc)
      } yield {
        page1._1.map(_.id) shouldBe List(b1.id, b2.id)
        page1._2 shouldBe true
        page2._1.map(_.id) shouldBe List(b3.id)
        page2._2 shouldBe false
      }
    }
  }
}
