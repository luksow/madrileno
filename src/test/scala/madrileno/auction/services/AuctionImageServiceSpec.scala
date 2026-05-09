package madrileno.auction.services

import cats.effect.std.UUIDGen
import cats.effect.testing.scalatest.AsyncIOSpec
import cats.effect.{Clock, IO}
import com.sksamuel.scrimage.ImmutableImage
import com.sksamuel.scrimage.nio.JpegWriter
import fs2.Stream
import io.opentelemetry.api.OpenTelemetry
import madrileno.auction.domain.*
import madrileno.auction.repositories.{AuctionImageRepository, AuctionRepository}
import madrileno.support.{TestData, TestGivens, TestObjectStoreRuntime, TestTransactor}
import madrileno.user.domain.User
import madrileno.user.repositories.UserRepository
import madrileno.utils.imaging.{Height, ImageFormat, Width}
import madrileno.utils.observability.TelemetryContext
import madrileno.utils.storage.{SignedUrlTtl, StorageKey}
import madrileno.utils.task.{Scheduler, SchedulerConfig}
import org.http4s.MediaType
import org.http4s.headers.`Content-Type`
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import org.typelevel.otel4s.metrics.Meter
import org.typelevel.otel4s.trace.Tracer

import java.awt.Color
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

  private lazy val schedulerClient = Scheduler(transactor, SchedulerConfig()).client

  private def freshService = {
    val runtime = TestObjectStoreRuntime.inMemory
    val service = new AuctionImageService(auctionRepo, imageRepo, runtime.objectStore, transactor, schedulerClient, SignedUrlTtl(5.minutes))
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
    service.attachImage(auctionId, sellerId, "wine.jpg", jpeg, Stream.emits(bytes))

  "AuctionImageService.attachImage" should {
    "attach an image and return Attached with computed size" in {
      val (service, _) = freshService
      val bytes        = "image-bytes".getBytes("UTF-8")
      for {
        seller  <- seedUser()
        auction <- seedAuction(seller.id)
        result  <- service.attachImage(auction.id, seller.id, "photo.jpg", jpeg, Stream.emits(bytes))
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

  "AuctionImageService.presignUpload" should {
    "return Presigned with a fresh imageId for the seller" in {
      val (service, _) = freshService
      for {
        seller  <- seedUser()
        auction <- seedAuction(seller.id)
        result  <- service.presignUpload(auction.id, seller.id, "wine.jpg", jpeg, contentLength = 1024L)
      } yield result match {
        case PresignUploadResult.Presigned(imageId, presigned) =>
          presigned.url.renderString should startWith(s"https://example.test/auctions/${auction.id}/images/$imageId")
          presigned.signedHeaders.get(org.typelevel.ci.CIString("content-length")).map(_.head.value) shouldBe Some("1024")
        case other => fail(s"Expected Presigned, got $other")
      }
    }

    "return AuctionNotFound for unknown auction" in {
      val (service, _) = freshService
      for {
        seller <- seedUser()
        result <- service.presignUpload(TestData.randomAuctionId(), seller.id, "wine.jpg", jpeg, 1024L)
      } yield result shouldBe PresignUploadResult.AuctionNotFound
    }

    "return NotOwner when caller is not the seller" in {
      val (service, _) = freshService
      for {
        seller  <- seedUser()
        other   <- seedUser()
        auction <- seedAuction(seller.id)
        result  <- service.presignUpload(auction.id, other.id, "wine.jpg", jpeg, 1024L)
      } yield result shouldBe PresignUploadResult.NotOwner
    }
  }

  "AuctionImageService.commitUpload" should {
    "return ObjectNotFound when the client never uploaded" in {
      val (service, _) = freshService
      for {
        seller  <- seedUser()
        auction <- seedAuction(seller.id)
        result <- service.presignUpload(auction.id, seller.id, "wine.jpg", jpeg, 1024L).flatMap {
                    case PresignUploadResult.Presigned(imageId, _) => service.commitUpload(auction.id, seller.id, imageId, "wine.jpg")
                    case other                                     => IO.raiseError(new AssertionError(s"setup: $other"))
                  }
      } yield result shouldBe CommitUploadResult.ObjectNotFound
    }

    "return Committed and persist the row when the upload landed" in {
      val (service, runtime) = freshService
      val bytes              = "uploaded".getBytes("UTF-8")
      for {
        seller  <- seedUser()
        auction <- seedAuction(seller.id)
        imageId <- service.presignUpload(auction.id, seller.id, "wine.jpg", jpeg, bytes.length.toLong).flatMap {
                     case PresignUploadResult.Presigned(id, _) => IO.pure(id)
                     case other                                => IO.raiseError(new AssertionError(s"setup: $other"))
                   }
        key = StorageKey(s"auctions/${auction.id}/images/$imageId")
        _      <- runtime.objectStore.put(key, jpeg, Stream.emits(bytes))
        result <- service.commitUpload(auction.id, seller.id, imageId, "wine.jpg")
        listed <- service.listImages(auction.id)
      } yield result match {
        case CommitUploadResult.Committed(image) =>
          image.fileName shouldBe "wine.jpg"
          image.sizeBytes shouldBe SizeBytes(bytes.length.toLong)
          image.position shouldBe ImagePosition(0)
          image.analyzedAt shouldBe None
          listed.map(_.id) shouldBe List(image.id)
        case other => fail(s"Expected Committed, got $other")
      }
    }

    "return NotOwner when caller is not the seller" in {
      val (service, runtime) = freshService
      val bytes              = "uploaded".getBytes("UTF-8")
      for {
        seller  <- seedUser()
        other   <- seedUser()
        auction <- seedAuction(seller.id)
        imageId <- service.presignUpload(auction.id, seller.id, "wine.jpg", jpeg, bytes.length.toLong).flatMap {
                     case PresignUploadResult.Presigned(id, _) => IO.pure(id)
                     case other                                => IO.raiseError(new AssertionError(s"setup: $other"))
                   }
        key = StorageKey(s"auctions/${auction.id}/images/$imageId")
        _      <- runtime.objectStore.put(key, jpeg, Stream.emits(bytes))
        result <- service.commitUpload(auction.id, other.id, imageId, "wine.jpg")
      } yield result shouldBe CommitUploadResult.NotOwner
    }

    "be idempotent — a second commit with the same imageId returns Committed with the existing row" in {
      val (service, runtime) = freshService
      val bytes              = "uploaded".getBytes("UTF-8")
      for {
        seller  <- seedUser()
        auction <- seedAuction(seller.id)
        imageId <- service.presignUpload(auction.id, seller.id, "wine.jpg", jpeg, bytes.length.toLong).flatMap {
                     case PresignUploadResult.Presigned(id, _) => IO.pure(id)
                     case other                                => IO.raiseError(new AssertionError(s"setup: $other"))
                   }
        key = StorageKey(s"auctions/${auction.id}/images/$imageId")
        _      <- runtime.objectStore.put(key, jpeg, Stream.emits(bytes))
        first  <- service.commitUpload(auction.id, seller.id, imageId, "wine.jpg")
        second <- service.commitUpload(auction.id, seller.id, imageId, "wine.jpg")
        listed <- service.listImages(auction.id)
      } yield (first, second) match {
        case (CommitUploadResult.Committed(a), CommitUploadResult.Committed(b)) =>
          a.id shouldBe b.id
          listed.map(_.id) shouldBe List(a.id)
        case other => fail(s"Expected two Committed, got $other")
      }
    }
  }

  "AuctionImageService.analyzeImageTask" should {
    "fill width/height/format/analyzedAt for a stored image" in {
      val (service, runtime) = freshService
      val jpegBytes          = ImmutableImage.filled(120, 80, Color.RED).bytes(JpegWriter.Default)
      for {
        seller  <- seedUser()
        auction <- seedAuction(seller.id)
        attached <- service.attachImage(auction.id, seller.id, "wine.jpg", jpeg, Stream.emits(jpegBytes)).flatMap {
                      case AttachImageResult.Attached(img) => IO.pure(img)
                      case other                           => IO.raiseError(new AssertionError(s"setup: $other"))
                    }
        _       <- service.analyzeImageTask.execution(service.analyzeImageTask.instance(s"analyze-${attached.id}", attached.id))
        refresh <- service.listImages(auction.id).map(_.find(_.id == attached.id))
      } yield {
        val _ = runtime
        refresh shouldBe defined
        refresh.flatMap(_.width) shouldBe Some(Width(120))
        refresh.flatMap(_.height) shouldBe Some(Height(80))
        refresh.flatMap(_.format) shouldBe Some(ImageFormat.Jpeg)
        refresh.flatMap(_.analyzedAt) shouldBe defined
      }
    }

    "be a no-op when the image is already analyzed" in {
      val (service, _) = freshService
      val jpegBytes    = ImmutableImage.filled(50, 50, Color.GREEN).bytes(JpegWriter.Default)
      for {
        seller  <- seedUser()
        auction <- seedAuction(seller.id)
        attached <- service.attachImage(auction.id, seller.id, "wine.jpg", jpeg, Stream.emits(jpegBytes)).flatMap {
                      case AttachImageResult.Attached(img) => IO.pure(img)
                      case other                           => IO.raiseError(new AssertionError(s"setup: $other"))
                    }
        _      <- service.analyzeImageTask.execution(service.analyzeImageTask.instance(s"analyze-1-${attached.id}", attached.id))
        first  <- service.listImages(auction.id).map(_.find(_.id == attached.id).flatMap(_.analyzedAt))
        _      <- service.analyzeImageTask.execution(service.analyzeImageTask.instance(s"analyze-2-${attached.id}", attached.id))
        second <- service.listImages(auction.id).map(_.find(_.id == attached.id).flatMap(_.analyzedAt))
      } yield {
        first shouldBe defined
        second shouldBe first
      }
    }

    "skip silently when the image row is missing" in {
      val (service, _) = freshService
      service.analyzeImageTask
        .execution(service.analyzeImageTask.instance("analyze-missing", TestData.randomAuctionImageId()))
        .as(succeed)
    }
  }

  "AuctionImageService.generateVariantTask" should {
    "produce a 256x256 thumb and store an AuctionImageVariant row" in {
      val (service, _) = freshService
      val landscape    = ImmutableImage.filled(400, 200, Color.RED).bytes(JpegWriter.Default)
      for {
        seller  <- seedUser()
        auction <- seedAuction(seller.id)
        attached <- service.attachImage(auction.id, seller.id, "wine.jpg", jpeg, Stream.emits(landscape)).flatMap {
                      case AttachImageResult.Attached(img) => IO.pure(img)
                      case other                           => IO.raiseError(new AssertionError(s"setup: $other"))
                    }
        _ <-
          service.generateVariantTask
            .execution(service.generateVariantTask.instance(s"v-${attached.id}-thumb", GenerateVariantPayload(attached.id, VariantSpec.Thumb)))
        listed <- service.listImagesWithVariants(auction.id)
      } yield {
        val variants = listed.find(_._1.id == attached.id).map(_._2).getOrElse(Nil)
        variants.find(_.spec == VariantSpec.Thumb) match {
          case Some(thumb) =>
            thumb.width shouldBe Width(256)
            thumb.height shouldBe Height(256)
            thumb.format shouldBe ImageFormat.Jpeg
          case None => fail(s"expected a Thumb variant, got: $variants")
        }
      }
    }

    "produce a Medium variant resized to 1024 on the long edge" in {
      val (service, _) = freshService
      val landscape    = ImmutableImage.filled(2000, 1000, Color.GREEN).bytes(JpegWriter.Default)
      for {
        seller  <- seedUser()
        auction <- seedAuction(seller.id)
        attached <- service.attachImage(auction.id, seller.id, "wine.jpg", jpeg, Stream.emits(landscape)).flatMap {
                      case AttachImageResult.Attached(img) => IO.pure(img)
                      case other                           => IO.raiseError(new AssertionError(s"setup: $other"))
                    }
        _ <- service.generateVariantTask
               .execution(service.generateVariantTask.instance(s"v-${attached.id}-medium", GenerateVariantPayload(attached.id, VariantSpec.Medium)))
        listed <- service.listImagesWithVariants(auction.id)
      } yield {
        val variants = listed.find(_._1.id == attached.id).map(_._2).getOrElse(Nil)
        variants.find(_.spec == VariantSpec.Medium) match {
          case Some(medium) =>
            medium.width shouldBe Width(1024)
            medium.height shouldBe Height(512)
          case None => fail(s"expected a Medium variant, got: $variants")
        }
      }
    }

    "be idempotent on a second invocation for the same (image, spec)" in {
      val (service, _) = freshService
      val bytes        = ImmutableImage.filled(400, 200, Color.BLUE).bytes(JpegWriter.Default)
      for {
        seller  <- seedUser()
        auction <- seedAuction(seller.id)
        attached <- service.attachImage(auction.id, seller.id, "wine.jpg", jpeg, Stream.emits(bytes)).flatMap {
                      case AttachImageResult.Attached(img) => IO.pure(img)
                      case other                           => IO.raiseError(new AssertionError(s"setup: $other"))
                    }
        runOnce =
          service.generateVariantTask
            .execution(service.generateVariantTask.instance(s"v-${attached.id}-thumb", GenerateVariantPayload(attached.id, VariantSpec.Thumb)))
        _      <- runOnce
        first  <- service.listImagesWithVariants(auction.id).map(_.find(_._1.id == attached.id).map(_._2.size).getOrElse(0))
        _      <- runOnce
        second <- service.listImagesWithVariants(auction.id).map(_.find(_._1.id == attached.id).map(_._2.size).getOrElse(0))
      } yield {
        first shouldBe 1
        second shouldBe 1
      }
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
