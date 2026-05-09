package madrileno.auction.routers.dto

import madrileno.auction.domain.AuctionImageId
import madrileno.utils.json.JsonProtocol.*

case class PresignedUploadDto(
  imageId: AuctionImageId,
  url: String,
  signedHeaders: Map[String, String])
    derives Encoder.AsObject,
      Decoder
