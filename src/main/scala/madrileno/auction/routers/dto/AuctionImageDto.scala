package madrileno.auction.routers.dto

import madrileno.auction.domain.*
import madrileno.utils.imaging.{Height, Width}
import madrileno.utils.json.JsonProtocol.*
import org.http4s.Header
import org.http4s.headers.`Content-Type`

import java.time.Instant

final case class VariantDto(
  spec: String,
  url: String,
  width: Width,
  height: Height,
  format: String)
    derives Encoder.AsObject,
      Decoder

final case class AuctionImageDto(
  id: AuctionImageId,
  auctionId: AuctionId,
  url: String,
  fileName: String,
  contentType: String,
  sizeBytes: SizeBytes,
  position: ImagePosition,
  uploadedAt: Instant,
  width: Option[Width],
  height: Option[Height],
  variants: List[VariantDto])
    derives Encoder.AsObject,
      Decoder

object AuctionImageDto {
  def apply(
    image: AuctionImage,
    apiPrefix: String,
    variants: List[AuctionImageVariant] = Nil
  ): AuctionImageDto =
    AuctionImageDto(
      id = image.id,
      auctionId = image.auctionId,
      url = s"/$apiPrefix/auctions/${image.auctionId}/images/${image.id}/content",
      fileName = image.fileName,
      contentType = Header[`Content-Type`].value(image.contentType),
      sizeBytes = image.sizeBytes,
      position = image.position,
      uploadedAt = image.uploadedAt,
      width = image.width,
      height = image.height,
      variants = variants
        .sortBy(_.spec.ordinal)
        .map(variant =>
          VariantDto(
            spec = variant.spec.toString,
            url = s"/$apiPrefix/auctions/${image.auctionId}/images/${image.id}/variants/${variant.spec}/content",
            width = variant.width,
            height = variant.height,
            format = variant.format.toString
          )
        )
    )
}

final case class ReorderImagesRequest(orderedIds: List[AuctionImageId]) derives Encoder.AsObject, Decoder
