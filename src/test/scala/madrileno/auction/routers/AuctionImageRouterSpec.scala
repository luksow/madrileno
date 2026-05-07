package madrileno.auction.routers

import cats.effect.std.UUIDGen
import cats.effect.testing.scalatest.AsyncIOSpec
import cats.effect.{Clock, IO}
import fs2.Stream
import io.opentelemetry.api.OpenTelemetry
import madrileno.auction.domain.*
import madrileno.auction.repositories.{AuctionImageRepository, AuctionRepository}
import madrileno.auction.routers.dto.*
import madrileno.auction.services.AuctionImageService
import madrileno.auth.domain.AuthContext
import madrileno.support.{TestData, TestGivens, TestObjectStoreRuntime, TestTransactor}
import madrileno.user.domain.User
import madrileno.user.repositories.UserRepository
import madrileno.utils.observability.TelemetryContext
import madrileno.utils.storage.SignedUrlTtl
import org.http4s.*
import org.http4s.circe.CirceEntityCodec.*
import org.http4s.headers.`Content-Type`
import org.http4s.implicits.*
import org.http4s.multipart.{Boundary, Multipart, Part}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import org.typelevel.otel4s.metrics.Meter
import org.typelevel.otel4s.trace.Tracer
import pl.iterators.stir.server.ToHttpRoutes

import java.time.Instant
import scala.concurrent.duration.DurationInt

class AuctionImageRouterSpec extends AsyncWordSpec with AsyncIOSpec with Matchers with TestTransactor {

  given Clock[IO]        = TestGivens.fixedClock()
  given UUIDGen[IO]      = TestGivens.deterministicUUIDs()
  given TelemetryContext = TelemetryContext(Meter.noop[IO], Tracer.noop[IO], OpenTelemetry.noop())

  private lazy val userRepo    = new UserRepository
  private lazy val auctionRepo = new AuctionRepository
  private lazy val imageRepo   = new AuctionImageRepository

  private case class Fixture(publicRoutes: HttpRoutes[IO], authedRoutes: AuthContext => HttpRoutes[IO])

  private def fixture(): Fixture = {
    val runtime = TestObjectStoreRuntime.inMemory
    val service = new AuctionImageService(auctionRepo, imageRepo, runtime.objectStore, transactor, SignedUrlTtl(5.minutes))
    val router  = new AuctionImageRouter(service, "v1")
    Fixture(publicRoutes = router.routes.toHttpRoutes, authedRoutes = ctx => router.authedRoutes(ctx).toHttpRoutes)
  }

  private def seedUser(user: User = TestData.user()): IO[User] =
    transactor.inSession(userRepo.create(user, Instant.now()))

  private def seedAuction(sellerId: madrileno.user.domain.UserId): IO[Auction] = {
    val auction = TestData.auction(sellerId = sellerId)
    transactor.inSession(auctionRepo.save(auction)).as(auction)
  }

  private def uploadRequest(
    auctionId: AuctionId,
    fileName: String = "wine.jpg",
    bytes: Array[Byte] = "image-data".getBytes("UTF-8")
  ): Request[IO] = {
    val part = Part.fileData[IO]("file", fileName, Stream.emits(bytes), `Content-Type`(MediaType.image.jpeg))
    val mp   = Multipart[IO](Vector(part), Boundary("madrileno-test-boundary"))
    Request[IO](method = Method.POST, uri = Uri.unsafeFromString(s"/auctions/$auctionId/images"))
      .withEntity(mp)
      .withHeaders(mp.headers)
  }

  "AuctionImageRouter" should {
    "list images for an auction" in {
      val f = fixture()
      for {
        seller  <- seedUser()
        auction <- seedAuction(seller.id)
        request = uploadRequest(auction.id)
        _        <- f.authedRoutes(AuthContext(seller)).orNotFound.run(request)
        listResp <- f.publicRoutes.orNotFound.run(Request[IO](Method.GET, Uri.unsafeFromString(s"/auctions/${auction.id}/images")))
        body     <- listResp.as[List[AuctionImageDto]]
      } yield {
        listResp.status shouldBe Status.Ok
        body.size shouldBe 1
        body.head.fileName shouldBe "wine.jpg"
      }
    }

    "POST a multipart upload as the seller and return Created" in {
      val f = fixture()
      for {
        seller  <- seedUser()
        auction <- seedAuction(seller.id)
        request = uploadRequest(auction.id)
        resp <- f.authedRoutes(AuthContext(seller)).orNotFound.run(request)
        body <- resp.as[AuctionImageDto]
      } yield {
        resp.status shouldBe Status.Created
        body.auctionId shouldBe auction.id
        body.fileName shouldBe "wine.jpg"
      }
    }

    "POST as a non-owner returns 403" in {
      val f = fixture()
      for {
        seller  <- seedUser()
        other   <- seedUser()
        auction <- seedAuction(seller.id)
        request = uploadRequest(auction.id)
        resp <- f.authedRoutes(AuthContext(other)).orNotFound.run(request)
      } yield resp.status shouldBe Status.Forbidden
    }

    "DELETE returns 204 for the seller and removes the image" in {
      val f = fixture()
      for {
        seller  <- seedUser()
        auction <- seedAuction(seller.id)
        upload = uploadRequest(auction.id)
        createResp <- f.authedRoutes(AuthContext(seller)).orNotFound.run(upload)
        created    <- createResp.as[AuctionImageDto]
        deleteReq = Request[IO](Method.DELETE, Uri.unsafeFromString(s"/auctions/${auction.id}/images/${created.id}"))
        deleteResp <- f.authedRoutes(AuthContext(seller)).orNotFound.run(deleteReq)
        listResp   <- f.publicRoutes.orNotFound.run(Request[IO](Method.GET, Uri.unsafeFromString(s"/auctions/${auction.id}/images")))
        list       <- listResp.as[List[AuctionImageDto]]
      } yield {
        deleteResp.status shouldBe Status.NoContent
        list shouldBe empty
      }
    }

    "PATCH order swaps positions" in {
      val f = fixture()
      for {
        seller  <- seedUser()
        auction <- seedAuction(seller.id)
        upload1 = uploadRequest(auction.id, fileName = "first.jpg")
        first <- f.authedRoutes(AuthContext(seller)).orNotFound.run(upload1).flatMap(_.as[AuctionImageDto])
        upload2 = uploadRequest(auction.id, fileName = "second.jpg")
        second <- f.authedRoutes(AuthContext(seller)).orNotFound.run(upload2).flatMap(_.as[AuctionImageDto])
        req = Request[IO](Method.PATCH, Uri.unsafeFromString(s"/auctions/${auction.id}/images/order"))
                .withEntity(ReorderImagesRequest(List(second.id, first.id)))
        resp     <- f.authedRoutes(AuthContext(seller)).orNotFound.run(req)
        listResp <- f.publicRoutes.orNotFound.run(Request[IO](Method.GET, Uri.unsafeFromString(s"/auctions/${auction.id}/images")))
        list     <- listResp.as[List[AuctionImageDto]]
      } yield {
        resp.status shouldBe Status.NoContent
        list.map(_.id) shouldBe List(second.id, first.id)
      }
    }

    "PATCH order with mismatched ids returns 400" in {
      val f = fixture()
      for {
        seller  <- seedUser()
        auction <- seedAuction(seller.id)
        upload = uploadRequest(auction.id)
        _ <- f.authedRoutes(AuthContext(seller)).orNotFound.run(upload)
        req = Request[IO](Method.PATCH, Uri.unsafeFromString(s"/auctions/${auction.id}/images/order"))
                .withEntity(ReorderImagesRequest(List(TestData.randomAuctionImageId())))
        resp <- f.authedRoutes(AuthContext(seller)).orNotFound.run(req)
      } yield resp.status shouldBe Status.BadRequest
    }

    "GET content streams the image bytes from the in-memory store" in {
      val f = fixture()
      for {
        seller  <- seedUser()
        auction <- seedAuction(seller.id)
        upload = uploadRequest(auction.id)
        createResp <- f.authedRoutes(AuthContext(seller)).orNotFound.run(upload)
        created    <- createResp.as[AuctionImageDto]
        getReq = Request[IO](Method.GET, Uri.unsafeFromString(s"/auctions/${auction.id}/images/${created.id}/content"))
        resp  <- f.publicRoutes.orNotFound.run(getReq)
        bytes <- resp.body.compile.to(Array)
      } yield {
        resp.status shouldBe Status.Ok
        resp.headers.get[`Content-Type`].map(_.mediaType) shouldBe Some(MediaType.image.jpeg)
        new String(bytes, "UTF-8") shouldBe "image-data"
      }
    }

    "GET content returns 404 when the image belongs to a different auction" in {
      val f = fixture()
      for {
        seller   <- seedUser()
        auctionA <- seedAuction(seller.id)
        auctionB <- seedAuction(seller.id)
        upload = uploadRequest(auctionA.id)
        createResp <- f.authedRoutes(AuthContext(seller)).orNotFound.run(upload)
        created    <- createResp.as[AuctionImageDto]
        getReq = Request[IO](Method.GET, Uri.unsafeFromString(s"/auctions/${auctionB.id}/images/${created.id}/content"))
        resp <- f.publicRoutes.orNotFound.run(getReq)
      } yield resp.status shouldBe Status.NotFound
    }
  }
}
