package madrileno.auction.services

import cats.effect.std.UUIDGen
import cats.effect.testing.scalatest.AsyncIOSpec
import cats.effect.{Clock, IO}
import fs2.Stream
import io.opentelemetry.api.OpenTelemetry
import madrileno.auction.domain.*
import madrileno.auction.repositories.{AuctionImageRepository, AuctionRepository}
import madrileno.support.{TestData, TestGivens, TestObjectStoreRuntime, TestTransactor}
import madrileno.user.domain.User
import madrileno.user.repositories.UserRepository
import madrileno.utils.observability.TelemetryContext
import madrileno.utils.storage.SignedUrlTtl
import org.http4s.MediaType
import org.http4s.headers.`Content-Type`
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import org.typelevel.otel4s.metrics.Meter
import org.typelevel.otel4s.trace.Tracer

import java.time.Instant
import scala.concurrent.duration.DurationInt

class AuctionImageServiceSpec extends AsyncWordSpec with AsyncIOSpec with Matchers with TestTransactor {

  given Clock[IO]        = TestGivens.fixedClock()
  given UUIDGen[IO]      = TestGivens.deterministicUUIDs()
  given TelemetryContext = TelemetryContext(Meter.noop[IO], Tracer.noop[IO], OpenTelemetry.noop())

  private lazy val userRepo    = new UserRepository
  private lazy val auctionRepo = new AuctionRepository
  private lazy val imageRepo   = new AuctionImageRepository

  private val jpeg = `Content-Type`(MediaType.image.jpeg)

  private def freshService = {
    val runtime = TestObjectStoreRuntime.inMemory
    val service = new AuctionImageService(auctionRepo, imageRepo, runtime.objectStore, transactor, SignedUrlTtl(5.minutes))
    (service, runtime)
  }

  private def seedUser(user: User = TestData.user()): IO[User] =
    transactor.inSession(userRepo.create(user, Instant.now()))

  private def seedAuction(sellerId: madrileno.user.domain.UserId): IO[Auction] = {
    val auction = TestData.auction(sellerId = sellerId)
    transactor.inSession(auctionRepo.save(auction)).as(auction)
  }

  private def attach(
    service: AuctionImageService,
    auctionId: AuctionId,
    sellerId: madrileno.user.domain.UserId,
    bytes: Array[Byte] = "data".getBytes("UTF-8")
  ): IO[AttachImageResult] =
    service.attachImage(auctionId, sellerId, "wine.jpg", jpeg, bytes.length.toLong, Stream.emits(bytes))

  "AuctionImageService.attachImage" should {
    "attach an image and return Attached with computed size" in {
      val (service, _) = freshService
      val bytes        = "image-bytes".getBytes("UTF-8")
      for {
        seller  <- seedUser()
        auction <- seedAuction(seller.id)
        result  <- service.attachImage(auction.id, seller.id, "photo.jpg", jpeg, bytes.length.toLong, Stream.emits(bytes))
      } yield result match {
        case AttachImageResult.Attached(image) =>
          image.auctionId shouldBe auction.id
          image.fileName shouldBe "photo.jpg"
          image.sizeBytes shouldBe SizeBytes(bytes.length.toLong)
          image.position shouldBe ImagePosition(0)
        case other => fail(s"Expected Attached, got $other")
      }
    }

    "assign incrementing positions to subsequent images" in {
      val (service, _) = freshService
      for {
        seller  <- seedUser()
        auction <- seedAuction(seller.id)
        first   <- attach(service, auction.id, seller.id)
        second  <- attach(service, auction.id, seller.id)
      } yield (first, second) match {
        case (AttachImageResult.Attached(a), AttachImageResult.Attached(b)) =>
          a.position shouldBe ImagePosition(0)
          b.position shouldBe ImagePosition(1)
        case other => fail(s"Expected two Attached, got $other")
      }
    }

    "return AuctionNotFound for unknown auction" in {
      val (service, _) = freshService
      for {
        seller <- seedUser()
        result <- attach(service, TestData.randomAuctionId(), seller.id)
      } yield result shouldBe AttachImageResult.AuctionNotFound
    }

    "return NotOwner when caller is not the seller" in {
      val (service, _) = freshService
      for {
        seller  <- seedUser()
        other   <- seedUser()
        auction <- seedAuction(seller.id)
        result  <- attach(service, auction.id, other.id)
      } yield result shouldBe AttachImageResult.NotOwner
    }
  }

  "AuctionImageService.detachImage" should {
    "soft-delete the image and remove it from listImages" in {
      val (service, _) = freshService
      for {
        seller  <- seedUser()
        auction <- seedAuction(seller.id)
        attached <- attach(service, auction.id, seller.id).flatMap {
                      case AttachImageResult.Attached(image) => IO.pure(image)
                      case other                             => IO.raiseError(new AssertionError(s"setup: $other"))
                    }
        result <- service.detachImage(auction.id, seller.id, attached.id)
        listed <- service.listImages(auction.id)
      } yield {
        result shouldBe DetachImageResult.Detached
        listed shouldBe empty
      }
    }

    "return NotOwner when caller is not the seller" in {
      val (service, _) = freshService
      for {
        seller  <- seedUser()
        other   <- seedUser()
        auction <- seedAuction(seller.id)
        attached <- attach(service, auction.id, seller.id).flatMap {
                      case AttachImageResult.Attached(image) => IO.pure(image)
                      case other                             => IO.raiseError(new AssertionError(s"setup: $other"))
                    }
        result <- service.detachImage(auction.id, other.id, attached.id)
      } yield result shouldBe DetachImageResult.NotOwner
    }

    "return NotFound when imageId belongs to a different auction" in {
      val (service, _) = freshService
      for {
        seller   <- seedUser()
        auctionA <- seedAuction(seller.id)
        auctionB <- seedAuction(seller.id)
        attached <- attach(service, auctionA.id, seller.id).flatMap {
                      case AttachImageResult.Attached(image) => IO.pure(image)
                      case other                             => IO.raiseError(new AssertionError(s"setup: $other"))
                    }
        result <- service.detachImage(auctionB.id, seller.id, attached.id)
      } yield result shouldBe DetachImageResult.NotFound
    }
  }

  "AuctionImageService.reorderImages" should {
    "reorder images according to the provided id list" in {
      val (service, _) = freshService
      for {
        seller  <- seedUser()
        auction <- seedAuction(seller.id)
        first <- attach(service, auction.id, seller.id).flatMap {
                   case AttachImageResult.Attached(image) => IO.pure(image)
                   case other                             => IO.raiseError(new AssertionError(s"setup: $other"))
                 }
        second <- attach(service, auction.id, seller.id).flatMap {
                    case AttachImageResult.Attached(image) => IO.pure(image)
                    case other                             => IO.raiseError(new AssertionError(s"setup: $other"))
                  }
        result <- service.reorderImages(auction.id, seller.id, List(second.id, first.id))
        listed <- service.listImages(auction.id)
      } yield {
        result shouldBe ReorderImagesResult.Reordered
        listed.map(_.id) shouldBe List(second.id, first.id)
      }
    }

    "return MismatchedIds when the id set doesn't match current images" in {
      val (service, _) = freshService
      for {
        seller  <- seedUser()
        auction <- seedAuction(seller.id)
        _       <- attach(service, auction.id, seller.id)
        result  <- service.reorderImages(auction.id, seller.id, List(TestData.randomAuctionImageId()))
      } yield result shouldBe ReorderImagesResult.MismatchedIds
    }

    "return NotOwner when caller is not the seller" in {
      val (service, _) = freshService
      for {
        seller  <- seedUser()
        other   <- seedUser()
        auction <- seedAuction(seller.id)
        result  <- service.reorderImages(auction.id, other.id, Nil)
      } yield result shouldBe ReorderImagesResult.NotOwner
    }
  }

  "AuctionImageService.serveImage" should {
    "return None when the image belongs to a different auction" in {
      val (service, _) = freshService
      for {
        seller   <- seedUser()
        auctionA <- seedAuction(seller.id)
        auctionB <- seedAuction(seller.id)
        attached <- attach(service, auctionA.id, seller.id).flatMap {
                      case AttachImageResult.Attached(image) => IO.pure(image)
                      case other                             => IO.raiseError(new AssertionError(s"setup: $other"))
                    }
        result <- service.serveImage(auctionB.id, attached.id)
      } yield result shouldBe None
    }

    "return None when the image doesn't exist" in {
      val (service, _) = freshService
      for {
        seller  <- seedUser()
        auction <- seedAuction(seller.id)
        result  <- service.serveImage(auction.id, TestData.randomAuctionImageId())
      } yield result shouldBe None
    }

    "return Streamed result when the image exists in the given auction" in {
      val (service, _) = freshService
      for {
        seller  <- seedUser()
        auction <- seedAuction(seller.id)
        attached <- attach(service, auction.id, seller.id).flatMap {
                      case AttachImageResult.Attached(image) => IO.pure(image)
                      case other                             => IO.raiseError(new AssertionError(s"setup: $other"))
                    }
        result <- service.serveImage(auction.id, attached.id)
      } yield result match {
        case Some(_: madrileno.utils.storage.ObjectStore.GetResult.Streamed) => succeed
        case other                                                           => fail(s"Expected Streamed, got $other")
      }
    }
  }
}
