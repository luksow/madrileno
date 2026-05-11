package madrileno.auction.repositories

import cats.effect.testing.scalatest.AsyncIOSpec
import madrileno.auction.domain.*
import madrileno.support.{TestData, TestTransactor}
import madrileno.user.domain.UserId
import madrileno.user.repositories.UserRepository
import madrileno.utils.db.dsl.{Column, FilteringRepository, KeysetSqlFilter, p}
import madrileno.utils.pagination.{CursorRequest, Limit, Offset, PageRequest, SortDirection}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import skunk.AppliedFragment

import java.time.Instant

class AuctionRepositorySpec extends AsyncWordSpec with AsyncIOSpec with Matchers with TestTransactor {

  private lazy val auctionRepo = new AuctionRepository
  private lazy val bidRepo     = new BidRepository
  private lazy val userRepo    = new UserRepository

  private lazy val auctionFilteringRepo: FilteringRepository[AuctionRow, AuctionRowFilter] =
    new FilteringRepository[AuctionRow, AuctionRowFilter] { override val table: AuctionRowTable.type = AuctionRowTable }

  private final case class AuctionKeysetFilter(
    sellerId: UserId,
    cursor: CursorRequest[(Instant, AuctionId)],
    direction: SortDirection = SortDirection.Desc)
      extends KeysetSqlFilter[Instant, AuctionId] {
    override protected def keysetCursor: CursorRequest[(Instant, AuctionId)]   = cursor
    override protected def keysetColumns: (Column[Instant], Column[AuctionId]) = (AuctionRowTable.createdAt, AuctionRowTable.id)
    override protected def keysetDirection: SortDirection                      = direction
    override protected def baseFilterFragment: AppliedFragment =
      fromPredicates((p.equal(sellerId) -> AuctionRowTable.sellerId, p.isNull[Instant] -> AuctionRowTable.deletedAt))
  }

  private lazy val auctionKeysetRepo: FilteringRepository[AuctionRow, AuctionKeysetFilter] =
    new FilteringRepository[AuctionRow, AuctionKeysetFilter] { override val table: AuctionRowTable.type = AuctionRowTable }

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

    "list auctions by status returns startingPrice when no bids exist" in withRollback {
      val seller = TestData.user()
      val open   = TestData.auction(sellerId = seller.id, status = AuctionStatus.Open, startingPrice = Price(BigDecimal(100)))
      val closed = TestData.auction(sellerId = seller.id, status = AuctionStatus.Closed)
      for {
        _      <- userRepo.create(seller, Instant.now())
        _      <- auctionRepo.save(open)
        _      <- auctionRepo.save(closed)
        result <- auctionRepo.list(status = Some(AuctionStatus.Open), sellerId = None, page = PageRequest.firstPageBy(AuctionSortField.CreatedAt))
      } yield {
        val (rows, total) = result
        rows.map(_._1.id) should contain only open.id
        rows.map(_._2) should contain only Price(BigDecimal(100))
        total shouldBe 1L
      }
    }

    "list auctions by seller returns the highest bid when bids exist" in withRollback {
      val seller1 = TestData.user()
      val seller2 = TestData.user()
      val bidder  = TestData.user()
      val a1      = TestData.auction(sellerId = seller1.id, startingPrice = Price(BigDecimal(100)))
      val a2      = TestData.auction(sellerId = seller2.id)
      val lowBid  = TestData.bid(auctionId = a1.id, bidderId = bidder.id, amount = Price(BigDecimal(150)))
      val highBid = TestData.bid(auctionId = a1.id, bidderId = bidder.id, amount = Price(BigDecimal(225)))
      for {
        _      <- userRepo.create(seller1, Instant.now())
        _      <- userRepo.create(seller2, Instant.now())
        _      <- userRepo.create(bidder, Instant.now())
        _      <- auctionRepo.save(a1)
        _      <- auctionRepo.save(a2)
        _      <- bidRepo.save(lowBid)
        _      <- bidRepo.save(highBid)
        result <- auctionRepo.list(status = None, sellerId = Some(seller1.id), page = PageRequest.firstPageBy(AuctionSortField.CreatedAt))
      } yield {
        val (rows, total) = result
        rows.map(_._1.id) should contain only a1.id
        rows.map(_._2) should contain only Price(BigDecimal(225))
        total shouldBe 1L
      }
    }

    "findPageByFilter returns the requested page in order plus the matching total" in withRollback {
      val seller = TestData.user()
      val older  = TestData.auction(sellerId = seller.id, createdAt = Instant.parse("2026-01-01T00:00:00Z"))
      val middle = TestData.auction(sellerId = seller.id, createdAt = Instant.parse("2026-02-01T00:00:00Z"))
      val newer  = TestData.auction(sellerId = seller.id, createdAt = Instant.parse("2026-03-01T00:00:00Z"))
      val filter =
        AuctionRowFilter(sellerId = p.equal(seller.id), page = Some(PageRequest(Limit(2), Offset(0), AuctionSortField.CreatedAt, SortDirection.Desc)))
      for {
        _      <- userRepo.create(seller, Instant.now())
        _      <- auctionRepo.save(older)
        _      <- auctionRepo.save(middle)
        _      <- auctionRepo.save(newer)
        result <- auctionFilteringRepo.findPageByFilter(filter)
      } yield {
        val (rows, total) = result
        rows.map(_.id) shouldBe List(newer.id, middle.id)
        total shouldBe 3L
      }
    }

    "findPageByFilter breaks sort-key ties with the primary key, following the sort direction" in withRollback {
      val seller = TestData.user()
      val at     = Instant.parse("2026-02-01T00:00:00Z")
      val a      = TestData.auction(sellerId = seller.id, createdAt = at)
      val b      = TestData.auction(sellerId = seller.id, createdAt = at)
      def filterBy(dir: SortDirection) =
        AuctionRowFilter(sellerId = p.equal(seller.id), page = Some(PageRequest(Limit(10), Offset(0), AuctionSortField.CreatedAt, dir)))
      for {
        _    <- userRepo.create(seller, Instant.now())
        _    <- auctionRepo.save(a)
        _    <- auctionRepo.save(b)
        asc  <- auctionFilteringRepo.findPageByFilter(filterBy(SortDirection.Asc))
        desc <- auctionFilteringRepo.findPageByFilter(filterBy(SortDirection.Desc))
      } yield {
        asc._1.map(_.id).toSet shouldBe Set(a.id, b.id)
        desc._1.map(_.id) shouldBe asc._1.map(_.id).reverse
      }
    }

    "findCursorPageByFilter walks the table in keyset order, flipping hasMore on the last page" in withRollback {
      val seller = TestData.user()
      val older  = TestData.auction(sellerId = seller.id, createdAt = Instant.parse("2026-01-01T00:00:00Z"))
      val middle = TestData.auction(sellerId = seller.id, createdAt = Instant.parse("2026-02-01T00:00:00Z"))
      val newer  = TestData.auction(sellerId = seller.id, createdAt = Instant.parse("2026-03-01T00:00:00Z"))
      for {
        _  <- userRepo.create(seller, Instant.now())
        _  <- auctionRepo.save(older)
        _  <- auctionRepo.save(middle)
        _  <- auctionRepo.save(newer)
        p1 <- auctionKeysetRepo.findCursorPageByFilter(AuctionKeysetFilter(seller.id, CursorRequest(Limit(2), None)))
        last1 = p1._1.last
        p2 <- auctionKeysetRepo.findCursorPageByFilter(AuctionKeysetFilter(seller.id, CursorRequest(Limit(2), Some((last1.createdAt, last1.id)))))
      } yield {
        p1._1.map(_.id) shouldBe List(newer.id, middle.id)
        p1._2 shouldBe true
        p2._1.map(_.id) shouldBe List(older.id)
        p2._2 shouldBe false
      }
    }

    "findCursorPageByFilter pages rows with the same sort key without skips or duplicates" in withRollback {
      val seller = TestData.user()
      val at     = Instant.parse("2026-02-01T00:00:00Z")
      val a      = TestData.auction(sellerId = seller.id, createdAt = at)
      val b      = TestData.auction(sellerId = seller.id, createdAt = at)
      val c      = TestData.auction(sellerId = seller.id, createdAt = at)
      for {
        _  <- userRepo.create(seller, Instant.now())
        _  <- auctionRepo.save(a)
        _  <- auctionRepo.save(b)
        _  <- auctionRepo.save(c)
        p1 <- auctionKeysetRepo.findCursorPageByFilter(AuctionKeysetFilter(seller.id, CursorRequest(Limit(2), None)))
        last1 = p1._1.last
        p2 <- auctionKeysetRepo.findCursorPageByFilter(AuctionKeysetFilter(seller.id, CursorRequest(Limit(2), Some((last1.createdAt, last1.id)))))
      } yield {
        val seen = p1._1.map(_.id) ++ p2._1.map(_.id)
        seen shouldBe seen.distinct
        seen.toSet shouldBe Set(a.id, b.id, c.id)
        p1._2 shouldBe true
        p2._1.size shouldBe 1
        p2._2 shouldBe false
      }
    }

    "findCursorPageByFilter walks ascending when keysetDirection is Asc" in withRollback {
      val seller = TestData.user()
      val older  = TestData.auction(sellerId = seller.id, createdAt = Instant.parse("2026-01-01T00:00:00Z"))
      val middle = TestData.auction(sellerId = seller.id, createdAt = Instant.parse("2026-02-01T00:00:00Z"))
      val newer  = TestData.auction(sellerId = seller.id, createdAt = Instant.parse("2026-03-01T00:00:00Z"))
      for {
        _  <- userRepo.create(seller, Instant.now())
        _  <- auctionRepo.save(older)
        _  <- auctionRepo.save(middle)
        _  <- auctionRepo.save(newer)
        p1 <- auctionKeysetRepo.findCursorPageByFilter(AuctionKeysetFilter(seller.id, CursorRequest(Limit(2), None), SortDirection.Asc))
        last1 = p1._1.last
        p2 <- auctionKeysetRepo.findCursorPageByFilter(
                AuctionKeysetFilter(seller.id, CursorRequest(Limit(2), Some((last1.createdAt, last1.id))), SortDirection.Asc)
              )
      } yield {
        p1._1.map(_.id) shouldBe List(older.id, middle.id)
        p1._2 shouldBe true
        p2._1.map(_.id) shouldBe List(newer.id)
        p2._2 shouldBe false
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
