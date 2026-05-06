package madrileno.auction.repositories

import cats.effect.testing.scalatest.AsyncIOSpec
import madrileno.auction.domain.*
import madrileno.support.{TestData, TestTransactor}
import madrileno.user.repositories.UserRepository
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
        _     <- auctionRepo.save(auction)
        found <- auctionRepo.find(auction.id)
      } yield {
        found shouldBe defined
        found.get.id shouldBe auction.id
        found.get.sellerId shouldBe seller.id
        found.get.wineName shouldBe auction.wineName
        found.get.status shouldBe AuctionStatus.Open
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
        result <- auctionRepo.list(status = Some(AuctionStatus.Open), sellerId = None)
      } yield {
        result.map(_._1.id) should contain only open.id
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
        result <- auctionRepo.list(status = None, sellerId = Some(seller1.id))
      } yield {
        result.map(_._1.id) should contain only a1.id
      }
    }

    "listExpired returns open auctions whose end time has passed" in withRollback {
      val seller  = TestData.user()
      val now     = Instant.now()
      val expired = TestData.auction(sellerId = seller.id, status = AuctionStatus.Open, endsAt = now.minusSeconds(60))
      val ongoing = TestData.auction(sellerId = seller.id, status = AuctionStatus.Open, endsAt = now.plusSeconds(3600))
      val closed  = TestData.auction(sellerId = seller.id, status = AuctionStatus.Closed, endsAt = now.minusSeconds(60))
      for {
        _      <- userRepo.create(seller, Instant.now())
        _      <- auctionRepo.save(expired)
        _      <- auctionRepo.save(ongoing)
        _      <- auctionRepo.save(closed)
        result <- auctionRepo.listExpired(now)
      } yield {
        result should contain only expired.id
      }
    }

    "update applies the transformation and persists it" in withRollback {
      val (seller, auction) = createAuctionWithSeller()
      for {
        _       <- userRepo.create(seller, Instant.now())
        _       <- auctionRepo.save(auction)
        result  <- auctionRepo.update[Nothing](auction.id, a => Right(a.copy(status = AuctionStatus.Closed)))
        updated <- auctionRepo.find(auction.id)
      } yield {
        result.flatMap(_.toOption).map(_.status) shouldBe Some(AuctionStatus.Closed)
        updated.get.status shouldBe AuctionStatus.Closed
      }
    }

    "update returns Some(Left(e)) when the transformation rejects, leaving the row untouched" in withRollback {
      val (seller, auction) = createAuctionWithSeller()
      case object Rejected
      for {
        _      <- userRepo.create(seller, Instant.now())
        _      <- auctionRepo.save(auction)
        result <- auctionRepo.update[Rejected.type](auction.id, _ => Left(Rejected))
        found  <- auctionRepo.find(auction.id)
      } yield {
        result shouldBe Some(Left(Rejected))
        found.get.status shouldBe AuctionStatus.Open
      }
    }

    "update returns None for a non-existent auction" in withRollback {
      val missingId = TestData.randomAuctionId()
      auctionRepo.update[Nothing](missingId, a => Right(a)).map(_ shouldBe None)
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
        vintage = Some(Vintage(1945)),
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
        val saved = found.get
        saved.wineName shouldBe WineName("Romanée-Conti")
        saved.vintage shouldBe Some(Vintage(1945))
        saved.color shouldBe WineColor.Red
        saved.region shouldBe Region("Bourgogne")
        saved.appellation shouldBe Appellation("Vosne-Romanée")
        saved.producerName shouldBe ProducerName("Domaine de la Romanée-Conti")
        saved.bottleSize shouldBe BottleSize.Magnum
        saved.bottleCount shouldBe BottleCount(3)
        saved.description shouldBe None
        saved.startingPrice shouldBe Price(BigDecimal(25000.50))
      }
    }
  }
}
