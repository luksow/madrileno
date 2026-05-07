package madrileno.auction.routers.dto

import madrileno.auction.domain.*
import madrileno.utils.json.JsonProtocol.*

import java.time.Instant

case class AuctionImageDto(
  id: AuctionImageId,
  auctionId: AuctionId,
  url: String,
  contentType: ContentType,
  sizeBytes: SizeBytes,
  position: ImagePosition,
  uploadedAt: Instant)
    derives Encoder.AsObject,
      Decoder

object AuctionImageDto {
  def apply(image: AuctionImage, apiVersion: String): AuctionImageDto =
    AuctionImageDto(
      id = image.id,
      auctionId = image.auctionId,
      url = s"/$apiVersion/auctions/${image.auctionId.unwrap}/images/${image.id.unwrap}/content",
      contentType = image.contentType,
      sizeBytes = image.sizeBytes,
      position = image.position,
      uploadedAt = image.uploadedAt
    )
}

case class ReorderImagesRequest(orderedIds: List[AuctionImageId]) derives Encoder.AsObject, Decoder
