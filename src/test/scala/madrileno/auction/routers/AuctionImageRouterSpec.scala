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
import madrileno.utils.imaging.{Height, ImageFormat, Width}
import madrileno.utils.json.JsonProtocol.*
import madrileno.utils.storage.StorageKey
import org.http4s.Method.*
import org.http4s.Status.*
import org.http4s.circe.CirceEntityCodec.*
import org.http4s.headers.`Content-Type`
import org.http4s.{EntityDecoder, MediaType}
import pl.iterators.baklava.{EmptyBody, FilePart, Multipart, TextPart}
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

  private def setupAuctionWithUploadedObject(): (Auction, AuctionImageId) = {
    val auction = setupAuction()
    val imageId = TestData.randomAuctionImageId()
    val key     = StorageKey(s"auctions/${auction.id}/images/$imageId")
    application.objectStore.put(key, jpeg, Stream.emits("image-data".getBytes("UTF-8"))).void.unsafeRunSync()
    (auction, imageId)
  }

  private def setupAuctionWithVariant(): (Auction, AuctionImage, AuctionImageVariant) = {
    val (auction, image) = setupAuctionWithImage()
    val variant          = AuctionImageVariant(
      id = TestData.randomAuctionImageVariantId(),
      auctionImageId = image.id,
      spec = VariantSpec.Thumb,
      storageKey = StorageKey(s"auctions/${auction.id}/images/${image.id}/thumb.jpg"),
      width = Width(256),
      height = Height(256),
      format = ImageFormat.Jpeg,
      generatedAt = Instant.now()
    )
    (application.transactor.inSession(imageRepository.saveVariant(variant)) *>
      application.objectStore.put(variant.storageKey, jpeg, Stream.emits("thumb-data".getBytes("UTF-8")))).void.unsafeRunSync()
    (auction, image, variant)
  }

  private val sampleUploadBody: Multipart =
    Multipart(FilePart("file", "image/jpeg", "wine.jpg", "image-data".getBytes("UTF-8")))

  private val samplePresignRequest: PresignUploadRequest =
    PresignUploadRequest(contentType = "image/jpeg", contentLength = 1024L)

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
        },
      withSetup(setupAuction())
        .request(auction =>
          onRequest(
            body = Multipart(TextPart("note", "no image attached")),
            security = bearer.apply(validJwt(sellerAuth)),
            pathParameters = auction.id
          )
        )
        .respondsWith[Error[Unit]](BadRequest, description = "Multipart body without a file part")
        .assert { case (ctx, _) =>
          val response = ctx.performRequest(allRoutes)
          response.body.title shouldBe Some("No file part found in multipart body")
        }
    )
  )

  path("/v1/auctions/{auctionId}/images/presign")(
    supports(
      POST,
      description =
        "Presign a direct-to-storage upload for a new auction image. The client PUTs the bytes to the returned URL with the signed headers, " +
          "then registers the image with the commit endpoint.",
      summary = "Seller-only: presign a direct upload",
      securitySchemes = Seq(bearerScheme),
      pathParameters = p[AuctionId]("auctionId"),
      tags = Seq("Auction images")
    )(
      withSetup(setupAuction())
        .request(auction => onRequest(body = samplePresignRequest, security = bearer.apply(validJwt(sellerAuth)), pathParameters = auction.id))
        .respondsWith[PresignedUploadDto](Created, description = "Upload presigned")
        .assert { case (ctx, auction) =>
          val response = ctx.performRequest(allRoutes)
          response.body.url should include(s"auctions/${auction.id}/images/${response.body.imageId}")
          response.body.signedHeaders.get("content-type") shouldBe Some("image/jpeg")
        },
      withSetup(setupAuction())
        .request(auction =>
          onRequest(
            body = PresignUploadRequest(contentType = "not a media type", contentLength = 1024L),
            security = bearer.apply(validJwt(sellerAuth)),
            pathParameters = auction.id
          )
        )
        .respondsWith[Error[Unit]](BadRequest, description = "Content type is not a media type")
        .assert { case (ctx, _) =>
          val response = ctx.performRequest(allRoutes)
          response.body.title shouldBe Some("Invalid Content-Type: not a media type")
        },
      withSetup(setupAuction())
        .request(auction =>
          onRequest(
            body = PresignUploadRequest(contentType = "image/jpeg", contentLength = 0L),
            security = bearer.apply(validJwt(sellerAuth)),
            pathParameters = auction.id
          )
        )
        .respondsWith[Error[Unit]](BadRequest, description = "Content length is not positive")
        .assert { case (ctx, _) =>
          val response = ctx.performRequest(allRoutes)
          response.body.title shouldBe Some("Content-Length must be positive")
        },
      onRequest(body = samplePresignRequest, security = bearer.apply(validJwt(sellerAuth)), pathParameters = TestData.randomAuctionId())
        .respondsWith[Error[Unit]](NotFound, description = "Auction not found")
        .assert { ctx =>
          val response = ctx.performRequest(allRoutes)
          response.body.title shouldBe Some("Auction not found")
        },
      withSetup(setupAuctionWithOtherSeeded())
        .request(auction => onRequest(body = samplePresignRequest, security = bearer.apply(validJwt(otherAuth)), pathParameters = auction.id))
        .respondsWith[Error[Unit]](Forbidden, description = "Presign attempted by non-owner")
        .assert { case (ctx, _) =>
          val response = ctx.performRequest(allRoutes)
          response.body.title shouldBe Some("Only the seller can upload images to this auction")
        }
    )
  )

  path("/v1/auctions/{auctionId}/images/commit")(
    supports(
      POST,
      description = "Register an image whose bytes were uploaded directly to storage through a presigned URL. " +
        "Committing an image id that is already attached to the same auction answers with that image again.",
      summary = "Seller-only: commit a presigned upload",
      securitySchemes = Seq(bearerScheme),
      pathParameters = p[AuctionId]("auctionId"),
      tags = Seq("Auction images")
    )(
      withSetup(setupAuctionWithUploadedObject())
        .request { case (auction, imageId) =>
          onRequest(body = CommitUploadRequest(imageId, "wine.jpg"), security = bearer.apply(validJwt(sellerAuth)), pathParameters = auction.id)
        }
        .respondsWith[AuctionImageDto](Created, description = "Image committed")
        .assert { case (ctx, (auction, imageId)) =>
          val response = ctx.performRequest(allRoutes)
          response.body.id shouldBe imageId
          response.body.auctionId shouldBe auction.id
          response.body.fileName shouldBe "wine.jpg"
        },
      withSetup(setupAuction())
        .request(auction =>
          onRequest(
            body = CommitUploadRequest(TestData.randomAuctionImageId(), "wine.jpg"),
            security = bearer.apply(validJwt(sellerAuth)),
            pathParameters = auction.id
          )
        )
        .respondsWith[Error[Unit]](NotFound, description = "No object at the image's storage key")
        .assert { case (ctx, _) =>
          val response = ctx.performRequest(allRoutes)
          response.body.title shouldBe Some("No object found at the expected key — did the direct upload complete?")
        },
      withSetup {
        val (_, image)   = setupAuctionWithImage()
        val otherAuction = setupAuction()
        (otherAuction, image)
      }.request { case (auction, image) =>
        onRequest(body = CommitUploadRequest(image.id, "wine.jpg"), security = bearer.apply(validJwt(sellerAuth)), pathParameters = auction.id)
      }.respondsWith[Error[Unit]](Conflict, description = "Image id already attached to another auction")
        .assert { case (ctx, _) =>
          val response = ctx.performRequest(allRoutes)
          response.body.title shouldBe Some("This image id is already in use; presign a fresh one")
        },
      onRequest(
        body = CommitUploadRequest(TestData.randomAuctionImageId(), "wine.jpg"),
        security = bearer.apply(validJwt(sellerAuth)),
        pathParameters = TestData.randomAuctionId()
      ).respondsWith[Error[Unit]](NotFound, description = "Auction not found")
        .assert { ctx =>
          val response = ctx.performRequest(allRoutes)
          response.body.title shouldBe Some("Auction not found")
        },
      withSetup(setupAuctionWithOtherSeeded())
        .request(auction =>
          onRequest(
            body = CommitUploadRequest(TestData.randomAuctionImageId(), "wine.jpg"),
            security = bearer.apply(validJwt(otherAuth)),
            pathParameters = auction.id
          )
        )
        .respondsWith[Error[Unit]](Forbidden, description = "Commit attempted by non-owner")
        .assert { case (ctx, _) =>
          val response = ctx.performRequest(allRoutes)
          response.body.title shouldBe Some("Only the seller can commit uploads for this auction")
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
        val _              = application.transactor
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

  path("/v1/auctions/{auctionId}/images/{imageId}/variants/{spec}/content")(
    supports(
      GET,
      description = "Stream or redirect to a generated variant of an image. Variants are generated asynchronously after upload, " +
        "so a freshly uploaded image answers 404 until they exist. Known variant names: " + VariantSpec.All.mkString(", "),
      summary = "Public: image variant content",
      pathParameters = (p[AuctionId]("auctionId"), p[AuctionImageId]("imageId"), p[String]("spec")),
      tags = Seq("Auction images")
    )(
      withSetup(setupAuctionWithVariant())
        .request { case (auction, image, variant) => onRequest(pathParameters = (auction.id, image.id, variant.spec.toString)) }
        .respondsWith[Array[Byte]](Ok, description = "Variant bytes (in-memory backend streams them)")(
          using EntityDecoder.byteArrayDecoder[IO],
          summon,
          summon
        )
        .assert { case (ctx, _) =>
          val response = ctx.performRequest(allRoutes)
          new String(response.body, "UTF-8") shouldBe "thumb-data"
        },
      withSetup(setupAuctionWithImage())
        .request { case (auction, image) => onRequest(pathParameters = (auction.id, image.id, VariantSpec.Thumb.toString)) }
        .respondsWith[Error[Unit]](NotFound, description = "Variant not generated yet")
        .assert { case (ctx, _) =>
          val response = ctx.performRequest(allRoutes)
          response.body.title shouldBe Some("Variant not found")
        },
      withSetup(setupAuctionWithImage())
        .request { case (auction, image) => onRequest(pathParameters = (auction.id, image.id, "poster")) }
        .respondsWith[Error[Unit]](NotFound, description = "Unknown variant name")
        .assert { case (ctx, _) =>
          val response = ctx.performRequest(allRoutes)
          response.body.title shouldBe Some("Unknown variant: poster")
        }
    )
  )
}
