package madrileno.auction.repositories

import cats.effect.testing.scalatest.AsyncIOSpec
import madrileno.auction.domain.*
import madrileno.support.{TestData, TestTransactor}
import madrileno.user.repositories.UserRepository
import madrileno.utils.imaging.{Height, ImageFormat, Width}
import madrileno.utils.storage.StorageKey
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec

import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

class AuctionImageRepositorySpec extends AsyncWordSpec with AsyncIOSpec with Matchers with TestTransactor {

  private lazy val auctionRepo = new AuctionRepository
  private lazy val imageRepo   = new AuctionImageRepository
  private lazy val userRepo    = new UserRepository

  private def setup() = {
    val seller  = TestData.user()
    val auction = TestData.auction(sellerId = seller.id)
    (seller, auction)
  }

  "AuctionImageRepository" should {
    "save and list images by auction in position order" in withRollback {
      val (seller, auction) = setup()
      val img0              = TestData.auctionImage(auctionId = auction.id, position = ImagePosition(0))
      val img1              = TestData.auctionImage(auctionId = auction.id, position = ImagePosition(1))
      for {
        _      <- userRepo.create(seller, Instant.now())
        _      <- auctionRepo.save(auction)
        _      <- imageRepo.save(img1)
        _      <- imageRepo.save(img0)
        result <- imageRepo.listByAuction(auction.id)
      } yield {
        result.map(_.id) shouldBe List(img0.id, img1.id)
      }
    }

    "nextPosition returns 0 when no images exist, then increments" in withRollback {
      val (seller, auction) = setup()
      for {
        _    <- userRepo.create(seller, Instant.now())
        _    <- auctionRepo.save(auction)
        pos0 <- imageRepo.nextPosition(auction.id)
        _    <- imageRepo.save(TestData.auctionImage(auctionId = auction.id, position = ImagePosition(pos0)))
        pos1 <- imageRepo.nextPosition(auction.id)
        _    <- imageRepo.save(TestData.auctionImage(auctionId = auction.id, position = ImagePosition(pos1)))
        pos2 <- imageRepo.nextPosition(auction.id)
      } yield {
        pos0 shouldBe 0
        pos1 shouldBe 1
        pos2 shouldBe 2
      }
    }

    "bulkSetPositions swaps positions atomically without violating the unique index" in withRollback {
      val (seller, auction) = setup()
      val img0              = TestData.auctionImage(auctionId = auction.id, position = ImagePosition(0))
      val img1              = TestData.auctionImage(auctionId = auction.id, position = ImagePosition(1))
      for {
        _     <- userRepo.create(seller, Instant.now())
        _     <- auctionRepo.save(auction)
        _     <- imageRepo.save(img0)
        _     <- imageRepo.save(img1)
        _     <- imageRepo.bulkSetPositions(auction.id, List(img1.id -> ImagePosition(0), img0.id -> ImagePosition(1)))
        found <- imageRepo.listByAuction(auction.id)
      } yield {
        found.map(_.id) shouldBe List(img1.id, img0.id)
      }
    }

    "softDelete hides image from find and listByAuction" in withRollback {
      val (seller, auction) = setup()
      val image             = TestData.auctionImage(auctionId = auction.id)
      for {
        _      <- userRepo.create(seller, Instant.now())
        _      <- auctionRepo.save(auction)
        _      <- imageRepo.save(image)
        _      <- imageRepo.softDelete(image.id, Instant.now())
        listed <- imageRepo.listByAuction(auction.id)
        found  <- imageRepo.find(image.id)
      } yield {
        listed shouldBe empty
        found shouldBe None
      }
    }

    "markAnalyzed populates width/height/format/analyzed_at" in withRollback {
      val (seller, auction) = setup()
      val image             = TestData.auctionImage(auctionId = auction.id)
      val now               = Instant.now().truncatedTo(ChronoUnit.MICROS)
      for {
        _     <- userRepo.create(seller, now)
        _     <- auctionRepo.save(auction)
        _     <- imageRepo.save(image)
        _     <- imageRepo.markAnalyzed(image.id, SizeBytes(2048L), Width(800), Height(600), ImageFormat.Jpeg, now)
        found <- imageRepo.find(image.id)
      } yield {
        found.flatMap(_.width) shouldBe Some(Width(800))
        found.flatMap(_.height) shouldBe Some(Height(600))
        found.flatMap(_.format) shouldBe Some(ImageFormat.Jpeg)
        found.flatMap(_.analyzedAt) shouldBe Some(now)
      }
    }

    "saveVariant + listVariants round-trip; findVariant by spec" in withRollback {
      val (seller, auction) = setup()
      val image             = TestData.auctionImage(auctionId = auction.id)
      val thumb = AuctionImageVariant(
        id = AuctionImageVariantId(UUID.randomUUID()),
        auctionImageId = image.id,
        spec = VariantSpec.Thumb,
        storageKey = StorageKey(s"auctions/${auction.id}/${image.id}/thumb.jpg"),
        width = Width(256),
        height = Height(256),
        format = ImageFormat.Jpeg,
        generatedAt = Instant.now().truncatedTo(ChronoUnit.MICROS)
      )
      val medium = thumb.copy(
        id = AuctionImageVariantId(UUID.randomUUID()),
        spec = VariantSpec.Medium,
        storageKey = StorageKey(s"auctions/${auction.id}/${image.id}/medium.jpg"),
        width = Width(1024),
        height = Height(768)
      )
      for {
        _      <- userRepo.create(seller, Instant.now())
        _      <- auctionRepo.save(auction)
        _      <- imageRepo.save(image)
        _      <- imageRepo.saveVariant(thumb)
        _      <- imageRepo.saveVariant(medium)
        listed <- imageRepo.listVariants(image.id)
        oneT   <- imageRepo.findVariant(image.id, VariantSpec.Thumb)
        oneM   <- imageRepo.findVariant(image.id, VariantSpec.Medium)
      } yield {
        listed.toSet shouldBe Set(thumb, medium)
        oneT shouldBe Some(thumb)
        oneM shouldBe Some(medium)
      }
    }
  }
}
