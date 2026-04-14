package madrileno.auction.repositories

import cats.effect.testing.scalatest.AsyncIOSpec
import madrileno.auction.domain.*
import madrileno.support.{TestData, TestTransactor}
import madrileno.user.repositories.UserRepository
import madrileno.utils.db.dsl.p
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec

import java.time.Instant

class AuctionRepositorySpec extends AsyncWordSpec with AsyncIOSpec with Matchers with TestTransactor {

  private lazy val auctionRepo = new AuctionRepository
  private lazy val userRepo    = new UserRepository

  private def createAuctionWithSeller() = {
    val seller  = TestData.user()
    val auction = TestData.auction(sellerId = seller.id)
    (seller, auction)
  }

  "AuctionRepository" should {
    "save and find an auction" in withRollback {
      val (seller, auction) = createAuctionWithSeller()
      for {
        _     <- userRepo.create(seller, Instant.now())
        row   <- auctionRepo.save(auction)
        found <- auctionRepo.find(auction.id)
      } yield {
        row.id shouldBe auction.id
        row.sellerId shouldBe seller.id
        row.wineName shouldBe auction.wineName
        row.vintage shouldBe auction.vintage
        row.color shouldBe auction.color
        row.region shouldBe auction.region
        row.appellation shouldBe auction.appellation
        row.producerName shouldBe auction.producerName
        row.startingPrice shouldBe auction.startingPrice
        row.currency shouldBe auction.currency
        row.status shouldBe AuctionStatus.Open
        found shouldBe defined
        found.get.id shouldBe auction.id
      }
    }

    "find returns None for non-existent auction" in withRollback {
      auctionRepo.find(TestData.randomAuctionId()).map(_ shouldBe None)
    }

    "list auctions by status" in withRollback {
      val seller = TestData.user()
      val open   = TestData.auction(sellerId = seller.id, status = AuctionStatus.Open)
      val closed = TestData.auction(sellerId = seller.id, status = AuctionStatus.Closed)
      for {
        _      <- userRepo.create(seller, Instant.now())
        _      <- auctionRepo.save(open)
        _      <- auctionRepo.save(closed)
        result <- auctionRepo.list(AuctionRowFilter(status = p.equal(AuctionStatus.Open), deletedAt = p.isNull))
      } yield {
        result.map(_.id) should contain only open.id
      }
    }

    "list auctions by seller" in withRollback {
      val seller1 = TestData.user()
      val seller2 = TestData.user()
      val a1      = TestData.auction(sellerId = seller1.id)
      val a2      = TestData.auction(sellerId = seller2.id)
      for {
        _      <- userRepo.create(seller1, Instant.now())
        _      <- userRepo.create(seller2, Instant.now())
        _      <- auctionRepo.save(a1)
        _      <- auctionRepo.save(a2)
        result <- auctionRepo.list(AuctionRowFilter(sellerId = p.equal(seller1.id), deletedAt = p.isNull))
      } yield {
        result.map(_.id) should contain only a1.id
      }
    }

    "update auction status" in withRollback {
      val (seller, auction) = createAuctionWithSeller()
      for {
        _       <- userRepo.create(seller, Instant.now())
        _       <- auctionRepo.save(auction)
        _       <- auctionRepo.update(auction.copy(status = AuctionStatus.Closed))
        updated <- auctionRepo.find(auction.id)
      } yield {
        updated shouldBe defined
        updated.get.status shouldBe AuctionStatus.Closed
      }
    }

    "soft delete hides auction from find" in withRollback {
      val (seller, auction) = createAuctionWithSeller()
      for {
        _     <- userRepo.create(seller, Instant.now())
        _     <- auctionRepo.save(auction)
        found <- auctionRepo.find(auction.id)
        _ = found shouldBe defined
        _     <- auctionRepo.softDelete(auction.id, Instant.now())
        after <- auctionRepo.find(auction.id)
      } yield after shouldBe None
    }

    "store and retrieve all wine properties correctly" in withRollback {
      val seller = TestData.user()
      val auction = TestData.auction(
        sellerId = seller.id,
        wineName = WineName("Romanée-Conti"),
        vintage = Vintage(1945),
        color = WineColor.Red,
        region = Region("Bourgogne"),
        appellation = Appellation("Vosne-Romanée"),
        producerName = ProducerName("Domaine de la Romanée-Conti"),
        bottleSize = BottleSize.Magnum,
        bottleCount = BottleCount(3),
        description = None,
        startingPrice = Price(BigDecimal(25000.50))
      )
      for {
        _     <- userRepo.create(seller, Instant.now())
        _     <- auctionRepo.save(auction)
        found <- auctionRepo.find(auction.id)
      } yield {
        val row = found.get
        row.wineName shouldBe WineName("Romanée-Conti")
        row.vintage shouldBe Vintage(1945)
        row.color shouldBe WineColor.Red
        row.region shouldBe Region("Bourgogne")
        row.appellation shouldBe Appellation("Vosne-Romanée")
        row.producerName shouldBe ProducerName("Domaine de la Romanée-Conti")
        row.bottleSize shouldBe BottleSize.Magnum
        row.bottleCount shouldBe BottleCount(3)
        row.description shouldBe None
        row.startingPrice shouldBe Price(BigDecimal(25000.50))
      }
    }
  }
}
