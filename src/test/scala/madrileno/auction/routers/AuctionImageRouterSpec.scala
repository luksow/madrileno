package madrileno.auction.routers

import cats.effect.IO
import fs2.Stream
import madrileno.auction.domain.*
import madrileno.auction.repositories.{AuctionImageRepository, AuctionRepository}
import madrileno.auction.routers.dto.*
import madrileno.auth.domain.AuthContext
import madrileno.support.{BaseRouteSpec, TestApplicationLoader, TestData}
import madrileno.user.domain.{User, UserId}
import madrileno.utils.db.transactor.DB
import madrileno.utils.http.Error
import madrileno.utils.json.JsonProtocol.*
import madrileno.utils.storage.StorageKey
import org.http4s.Method.*
import org.http4s.Status.*
import org.http4s.circe.CirceEntityCodec.*
import org.http4s.headers.`Content-Type`
import org.http4s.{EntityDecoder, MediaType}
import pl.iterators.baklava.{EmptyBody, FilePart, Multipart}
import pl.iterators.stir.server.Route

import java.time.Instant

class AuctionImageRouterSpec extends BaseRouteSpec with TestApplicationLoader {

  override def route: Route = application.routes(wsb)

  private val seller     = TestData.user()
  private val other      = TestData.user()
  private val sellerAuth = AuthContext(seller)
  private val otherAuth  = AuthContext(other)

  private val auctionRepository = new AuctionRepository
  private val imageRepository   = new AuctionImageRepository

  private val jpeg = `Content-Type`(MediaType.image.jpeg)

  private def seedUser(user: User): DB[User] =
    application.userRepository.find(user.id).flatMap {
      case Some(existing) => IO.pure(existing)
      case None           => application.userRepository.create(user, Instant.now())
    }

  private def seedAuction(id: AuctionId, sellerId: UserId): DB[Auction] = {
    val auction = TestData.auction(id = id, sellerId = sellerId)
    auctionRepository.save(auction).as(auction)
  }

  private def seedImage(
    auction: Auction,
    fileName: String,
    bytes: Array[Byte],
    position: ImagePosition
  ): IO[AuctionImage] = {
    val image = TestData.auctionImage(
      auctionId = auction.id,
      storageKey = StorageKey(s"auctions/${auction.id}/images/${TestData.randomAuctionImageId()}"),
      fileName = fileName,
      contentType = jpeg,
      sizeBytes = SizeBytes(bytes.length.toLong),
      position = position
    )
    application.transactor.inSession(imageRepository.save(image)) *>
      application.objectStore.put(image.storageKey, jpeg, Stream.emits(bytes)) *>
      IO.pure(image)
  }

  private def setupAuction(): Auction = {
    val id = TestData.randomAuctionId()
    application.transactor.inSession(seedUser(seller) *> seedAuction(id, seller.id)).unsafeRunSync()
  }

  private def setupAuctionWithImage(fileName: String = "wine.jpg", bytes: Array[Byte] = "image-data".getBytes("UTF-8")): (Auction, AuctionImage) = {
    val auction = setupAuction()
    val image   = seedImage(auction, fileName, bytes, ImagePosition(0)).unsafeRunSync()
    (auction, image)
  }

  private def setupAuctionWithTwoImages(): (Auction, AuctionImage, AuctionImage) = {
    val auction = setupAuction()
    val first   = seedImage(auction, "first.jpg", "first".getBytes("UTF-8"), ImagePosition(0)).unsafeRunSync()
    val second  = seedImage(auction, "second.jpg", "second".getBytes("UTF-8"), ImagePosition(1)).unsafeRunSync()
    (auction, first, second)
  }

  private def setupAuctionWithOtherSeeded(): Auction = {
    val id = TestData.randomAuctionId()
    application.transactor.inSession(seedUser(seller) *> seedUser(other) *> seedAuction(id, seller.id)).unsafeRunSync()
  }

  private val sampleUploadBody: Multipart =
    Multipart(FilePart("file", "image/jpeg", "wine.jpg", "image-data".getBytes("UTF-8")))

  path("/v1/auctions/{auctionId}/images")(
    supports(
      GET,
      description = "List images attached to an auction in display order",
      summary = "Public: returns auction images sorted by position",
      pathParameters = p[AuctionId]("auctionId"),
      tags = Seq("Auction images")
    )(
      withSetup(setupAuctionWithTwoImages())
        .request { case (auction, _, _) => onRequest(pathParameters = auction.id) }
        .respondsWith[List[AuctionImageDto]](Ok, description = "Images in position order")
        .assert { case (ctx, (_, first, second)) =>
          val response = ctx.performRequest(allRoutes)
          response.body.map(_.id) shouldBe List(first.id, second.id)
        }
    ),
    supports(
      POST,
      description = "Attach an image to an auction (multipart upload)",
      summary = "Seller-only: uploads bytes to object storage and persists the row",
      securitySchemes = Seq(bearerScheme),
      pathParameters = p[AuctionId]("auctionId"),
      tags = Seq("Auction images")
    )(
      withSetup(setupAuction())
        .request(auction => onRequest(body = sampleUploadBody, security = bearer.apply(validJwt(sellerAuth)), pathParameters = auction.id))
        .respondsWith[AuctionImageDto](Created, description = "Image attached")
        .assert { case (ctx, auction) =>
          val response = ctx.performRequest(allRoutes)
          response.body.auctionId shouldBe auction.id
          response.body.fileName shouldBe "wine.jpg"
        },
      onRequest(body = sampleUploadBody, security = bearer.apply(validJwt(sellerAuth)), pathParameters = TestData.randomAuctionId())
        .respondsWith[Error[Unit]](NotFound, description = "Auction not found")
        .assert { ctx =>
          val response = ctx.performRequest(allRoutes)
          response.body.title shouldBe Some("Auction not found")
        },
      withSetup(setupAuctionWithOtherSeeded())
        .request(auction => onRequest(body = sampleUploadBody, security = bearer.apply(validJwt(otherAuth)), pathParameters = auction.id))
        .respondsWith[Error[Unit]](Forbidden, description = "Upload attempted by non-owner")
        .assert { case (ctx, _) =>
          val response = ctx.performRequest(allRoutes)
          response.body.title shouldBe Some("Only the seller can attach images to this auction")
        }
    )
  )

  path("/v1/auctions/{auctionId}/images/{imageId}")(
    supports(
      DELETE,
      description = "Soft-delete an image and remove it from object storage",
      summary = "Seller-only: detaches an image from the auction",
      securitySchemes = Seq(bearerScheme),
      pathParameters = (p[AuctionId]("auctionId"), p[AuctionImageId]("imageId")),
      tags = Seq("Auction images")
    )(
      withSetup(setupAuctionWithImage())
        .request { case (auction, image) =>
          onRequest(security = bearer.apply(validJwt(sellerAuth)), pathParameters = (auction.id, image.id))
        }
        .respondsWith[EmptyBody](NoContent, description = "Image detached")
        .assert { case (ctx, _) => ctx.performRequest(allRoutes) },
      onRequest(security = bearer.apply(validJwt(sellerAuth)), pathParameters = (TestData.randomAuctionId(), TestData.randomAuctionImageId()))
        .respondsWith[Error[Unit]](NotFound, description = "Image not found")
        .assert { ctx =>
          val response = ctx.performRequest(allRoutes)
          response.body.title shouldBe Some("Image not found")
        },
      withSetup(setupAuctionWithImage()) // seller-owned auction; otherAuth is unrelated
        .request { case (auction, image) =>
          onRequest(security = bearer.apply(validJwt(otherAuth)), pathParameters = (auction.id, image.id))
        }
        .respondsWith[Error[Unit]](Forbidden, description = "Detach attempted by non-owner")
        .assert { case (ctx, _) =>
          val response = ctx.performRequest(allRoutes)
          response.body.title shouldBe Some("Only the seller can detach images from this auction")
        }
    )
  )

  path("/v1/auctions/{auctionId}/images/order")(
    supports(
      PATCH,
      description = "Reorder images on an auction (must include every active image id)",
      summary = "Seller-only: bulk position update with mismatched-id guard",
      securitySchemes = Seq(bearerScheme),
      pathParameters = p[AuctionId]("auctionId"),
      tags = Seq("Auction images")
    )(
      withSetup(setupAuctionWithTwoImages())
        .request { case (auction, first, second) =>
          onRequest(
            body = ReorderImagesRequest(List(second.id, first.id)),
            security = bearer.apply(validJwt(sellerAuth)),
            pathParameters = auction.id
          )
        }
        .respondsWith[EmptyBody](NoContent, description = "Reorder applied")
        .assert { case (ctx, _) => ctx.performRequest(allRoutes) },
      withSetup(setupAuctionWithImage())
        .request { case (auction, _) =>
          onRequest(
            body = ReorderImagesRequest(List(TestData.randomAuctionImageId())),
            security = bearer.apply(validJwt(sellerAuth)),
            pathParameters = auction.id
          )
        }
        .respondsWith[Error[Unit]](BadRequest, description = "Mismatched image ids")
        .assert { case (ctx, _) =>
          val response = ctx.performRequest(allRoutes)
          response.body.title shouldBe Some("Reorder list must contain exactly the existing image ids")
        }
    )
  )

  path("/v1/auctions/{auctionId}/images/{imageId}/content")(
    supports(
      GET,
      description = "Stream or redirect to image bytes. The disk / in-memory backend streams; S3 returns 303 SeeOther with a presigned Location.",
      summary = "Public: image content",
      pathParameters = (p[AuctionId]("auctionId"), p[AuctionImageId]("imageId")),
      tags = Seq("Auction images")
    )(
      withSetup(setupAuctionWithImage())
        .request { case (auction, image) => onRequest(pathParameters = (auction.id, image.id)) }
        .respondsWith[Array[Byte]](Ok, description = "Image bytes (in-memory backend streams them)")(
          using EntityDecoder.byteArrayDecoder[IO],
          summon,
          summon
        )
        .assert { case (ctx, _) =>
          val response = ctx.performRequest(allRoutes)
          new String(response.body, "UTF-8") shouldBe "image-data"
        },
      withSetup {
        val (_, image)     = setupAuctionWithImage()
        val otherAuctionId = TestData.randomAuctionId()
        val _ = application.transactor
          .inSession(seedUser(seller) *> seedAuction(otherAuctionId, seller.id))
          .unsafeRunSync()
        (otherAuctionId, image)
      }.request { case (otherAuctionId, image) =>
        onRequest(pathParameters = (otherAuctionId, image.id))
      }.respondsWith[Error[Unit]](NotFound, description = "Image not found in this auction")
        .assert { case (ctx, _) =>
          val response = ctx.performRequest(allRoutes)
          response.body.title shouldBe Some("Image not found")
        }
    )
  )
}
