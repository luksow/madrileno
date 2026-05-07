package madrileno.auction.routers

import cats.effect.IO
import madrileno.auction.domain.*
import madrileno.auction.routers.dto.*
import madrileno.auction.services.*
import madrileno.auth.domain.AuthContext
import madrileno.utils.http.BaseRouter
import madrileno.utils.observability.TelemetryContext
import org.http4s.headers.{Location, `Content-Disposition`, `Content-Type`}
import org.http4s.multipart.{Multipart, Part}
import org.http4s.{Headers, MediaType, Response, Status}
import org.typelevel.ci.CIString
import pl.iterators.stir.marshalling.ToResponseMarshallable
import pl.iterators.stir.server.Route

class AuctionImageRouter(auctionImageService: AuctionImageService, apiVersion: String)(using TelemetryContext) extends BaseRouter {

  val routes: Route = {
    (get & path("auctions" / JavaUUID.as[AuctionId] / "images") & pathEndOrSingleSlash) { auctionId =>
      complete {
        auctionImageService.listImages(auctionId).map[ToResponseMarshallable] { images =>
          Ok -> images.map(AuctionImageDto(_, apiVersion))
        }
      }
    } ~
      (get & path("auctions" / JavaUUID.as[AuctionId] / "images" / JavaUUID.as[AuctionImageId] / "content") & pathEndOrSingleSlash) { (_, imageId) =>
        complete {
          auctionImageService.serveImage(imageId).map[ToResponseMarshallable] {
            case None => error(NotFound, "image-not-found", "Image not found")
            case Some(ServeImageResult.Redirected(url)) =>
              Response[IO](Status.SeeOther, headers = Headers(Location(url)))
            case Some(ServeImageResult.Streamed(ct, fileName, body)) =>
              Response[IO](
                Status.Ok,
                headers =
                  Headers(`Content-Type`(ct.mediaType, ct.charset), `Content-Disposition`("attachment", Map(CIString("filename") -> fileName))),
                body = body
              )
          }
        }
      }
  }

  def authedRoutes(authContext: AuthContext): Route = {
    (post & path("auctions" / JavaUUID.as[AuctionId] / "images") & pathEndOrSingleSlash & entity(as[Multipart[IO]])) { (auctionId, multipart) =>
      complete {
        firstFilePart(multipart) match {
          case None => error(BadRequest, "missing-file", "No file part found in multipart body")
          case Some(part) =>
            val contentType   = part.headers.get[`Content-Type`].getOrElse(`Content-Type`(MediaType.application.`octet-stream`))
            val sizeBytesHint = part.headers.get[org.http4s.headers.`Content-Length`].map(_.length).getOrElse(0L)
            val fileName      = part.filename.getOrElse("file")
            auctionImageService
              .attachImage(auctionId, authContext.userId, fileName, contentType, sizeBytesHint, part.body)
              .map[ToResponseMarshallable] {
                case AttachImageResult.Attached(image) => Created -> AuctionImageDto(image, apiVersion)
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
      }
  }

  private def firstFilePart(multipart: Multipart[IO]): Option[Part[IO]] =
    multipart.parts.find(_.filename.isDefined).orElse(multipart.parts.find(_.name.contains("file"))).orElse(multipart.parts.headOption)
}
