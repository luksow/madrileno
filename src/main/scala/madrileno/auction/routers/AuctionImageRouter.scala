package madrileno.auction.routers

import cats.effect.IO
import madrileno.auction.domain.*
import madrileno.auction.routers.dto.*
import madrileno.auction.services.*
import madrileno.auth.domain.AuthContext
import madrileno.utils.http.BaseRouter
import madrileno.utils.observability.TelemetryContext
import madrileno.utils.storage.ObjectStore
import org.http4s.headers.{Location, `Content-Disposition`, `Content-Type`}
import org.http4s.multipart.{Multipart, Part}
import org.http4s.{Headers, MediaType, Response, Status}
import org.typelevel.ci.*
import pl.iterators.stir.marshalling.ToResponseMarshallable
import pl.iterators.stir.server.Route

class AuctionImageRouter(auctionImageService: AuctionImageService, apiPrefix: String)(using TelemetryContext) extends BaseRouter {

  val routes: Route = {
    (get & path("auctions" / JavaUUID.as[AuctionId] / "images") & pathEndOrSingleSlash) { auctionId =>
      complete {
        auctionImageService.listImagesWithVariants(auctionId).map[ToResponseMarshallable] { pairs =>
          Ok -> pairs.map { case (image, variants) => AuctionImageDto(image, apiPrefix, variants) }
        }
      }
    } ~
      (get & path("auctions" / JavaUUID.as[AuctionId] / "images" / JavaUUID.as[AuctionImageId] / "content") & pathEndOrSingleSlash) {
        (auctionId, imageId) =>
          complete {
            renderGetResult(auctionImageService.serveImage(auctionId, imageId))
          }
      } ~
      (get & path(
        "auctions" / JavaUUID.as[AuctionId] / "images" / JavaUUID.as[AuctionImageId] / "variants" / Segment / "content"
      ) & pathEndOrSingleSlash) {
        (
          auctionId,
          imageId,
          specSegment
        ) =>
          complete {
            VariantSpec.byName(specSegment) match {
              case None       => IO.pure[ToResponseMarshallable](error(NotFound, "variant-not-found", s"Unknown variant: $specSegment"))
              case Some(spec) => renderGetResult(auctionImageService.serveVariant(auctionId, imageId, spec))
            }
          }
      }
  }

  private def renderGetResult(io: IO[Option[ObjectStore.GetResult]]): IO[ToResponseMarshallable] =
    io.map[ToResponseMarshallable] {
      case None | Some(ObjectStore.GetResult.NotFound) => error(NotFound, "image-not-found", "Image not found")
      case Some(ObjectStore.GetResult.Redirected(url)) => Response[IO](Status.SeeOther, headers = Headers(Location(url)))
      case Some(ObjectStore.GetResult.Streamed(ct, fileName, body)) =>
        val baseHeaders = Headers(ct)
        val headers     = fileName.fold(baseHeaders)(name => baseHeaders.put(`Content-Disposition`("attachment", Map(ci"filename" -> name))))
        Response[IO](Status.Ok, headers = headers, body = body)
    }

  def authedRoutes(authContext: AuthContext): Route = {
    (post & path("auctions" / JavaUUID.as[AuctionId] / "images") & pathEndOrSingleSlash & entity(as[Multipart[IO]])) { (auctionId, multipart) =>
      complete {
        firstFilePart(multipart) match {
          case None => error(BadRequest, "missing-file", "No file part found in multipart body")
          case Some(part) =>
            val contentType = part.headers.get[`Content-Type`].getOrElse(`Content-Type`(MediaType.application.`octet-stream`))
            val fileName    = part.filename.getOrElse("file")
            auctionImageService
              .attachImage(auctionId, authContext.userId, fileName, contentType, part.body)
              .map[ToResponseMarshallable] {
                case AttachImageResult.Attached(image) => Created -> AuctionImageDto(image, apiPrefix)
                case AttachImageResult.AuctionNotFound => error(NotFound, "auction-not-found", "Auction not found")
                case AttachImageResult.NotOwner        => error(Forbidden, "not-owner", "Only the seller can attach images to this auction")
              }
        }
      }
    } ~
      (delete & path("auctions" / JavaUUID.as[AuctionId] / "images" / JavaUUID.as[AuctionImageId]) & pathEndOrSingleSlash) { (auctionId, imageId) =>
        complete {
          auctionImageService.detachImage(auctionId, authContext.userId, imageId).map[ToResponseMarshallable] {
            case DetachImageResult.Detached => NoContent
            case DetachImageResult.NotFound => error(NotFound, "image-not-found", "Image not found")
            case DetachImageResult.NotOwner => error(Forbidden, "not-owner", "Only the seller can detach images from this auction")
          }
        }
      } ~
      (patch & path("auctions" / JavaUUID.as[AuctionId] / "images" / "order") & pathEndOrSingleSlash & entity(as[ReorderImagesRequest])) {
        (auctionId, request) =>
          complete {
            auctionImageService.reorderImages(auctionId, authContext.userId, request.orderedIds).map[ToResponseMarshallable] {
              case ReorderImagesResult.Reordered       => NoContent
              case ReorderImagesResult.AuctionNotFound => error(NotFound, "auction-not-found", "Auction not found")
              case ReorderImagesResult.NotOwner        => error(Forbidden, "not-owner", "Only the seller can reorder images for this auction")
              case ReorderImagesResult.MismatchedIds =>
                error(BadRequest, "mismatched-ids", "Reorder list must contain exactly the existing image ids")
            }
          }
      } ~
      (post & path("auctions" / JavaUUID.as[AuctionId] / "images" / "presign") & pathEndOrSingleSlash & entity(as[PresignUploadRequest])) {
        (auctionId, request) =>
          complete {
            `Content-Type`.parse(request.contentType).toOption match {
              case None => IO.pure[ToResponseMarshallable](error(BadRequest, "invalid-content-type", s"Invalid Content-Type: ${request.contentType}"))
              case _ if request.contentLength <= 0 =>
                IO.pure[ToResponseMarshallable](error(BadRequest, "invalid-content-length", "Content-Length must be positive"))
              case Some(ct) =>
                auctionImageService
                  .presignUpload(auctionId, authContext.userId, request.fileName, ct, request.contentLength)
                  .map[ToResponseMarshallable] {
                    case PresignUploadResult.Presigned(imageId, presigned) =>
                      Created -> PresignedUploadDto(
                        imageId = imageId,
                        url = presigned.url.renderString,
                        signedHeaders = presigned.signedHeaders.headers.map(h => h.name.toString -> h.value).toMap
                      )
                    case PresignUploadResult.AuctionNotFound => error(NotFound, "auction-not-found", "Auction not found")
                    case PresignUploadResult.NotOwner        => error(Forbidden, "not-owner", "Only the seller can upload images to this auction")
                  }
            }
          }
      } ~
      (post & path("auctions" / JavaUUID.as[AuctionId] / "images" / "commit") & pathEndOrSingleSlash & entity(as[CommitUploadRequest])) {
        (auctionId, request) =>
          complete {
            auctionImageService
              .commitUpload(auctionId, authContext.userId, request.imageId, request.fileName)
              .map[ToResponseMarshallable] {
                case CommitUploadResult.Committed(image) => Created -> AuctionImageDto(image, apiPrefix)
                case CommitUploadResult.AuctionNotFound  => error(NotFound, "auction-not-found", "Auction not found")
                case CommitUploadResult.NotOwner         => error(Forbidden, "not-owner", "Only the seller can commit uploads for this auction")
                case CommitUploadResult.ObjectNotFound =>
                  error(NotFound, "object-not-found", "No object found at the expected key — did the direct upload complete?")
              }
          }
      }
  }

  private def firstFilePart(multipart: Multipart[IO]): Option[Part[IO]] =
    multipart.parts.find(_.filename.isDefined).orElse(multipart.parts.find(_.name.contains("file")))
}
