package madrileno.auction.routers.dto

import madrileno.auction.domain.*
import madrileno.utils.json.JsonProtocol.*
import org.http4s.Header
import org.http4s.headers.`Content-Type`

import java.time.Instant

case class AuctionImageDto(
  id: AuctionImageId,
  auctionId: AuctionId,
  url: String,
  fileName: String,
  contentType: String,
  sizeBytes: SizeBytes,
  position: ImagePosition,
  uploadedAt: Instant)
    derives Encoder.AsObject,
      Decoder

object AuctionImageDto {
  def apply(image: AuctionImage, apiPrefix: String): AuctionImageDto =
    AuctionImageDto(
      id = image.id,
      auctionId = image.auctionId,
      url = s"/$apiPrefix/auctions/${image.auctionId}/images/${image.id}/content",
      fileName = image.fileName,
      contentType = Header[`Content-Type`].value(image.contentType),
      sizeBytes = image.sizeBytes,
      position = image.position,
      uploadedAt = image.uploadedAt
    )
}

case class ReorderImagesRequest(orderedIds: List[AuctionImageId]) derives Encoder.AsObject, Decoder
