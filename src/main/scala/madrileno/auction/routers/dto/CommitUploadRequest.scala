package madrileno.auction.routers.dto

import madrileno.auction.domain.AuctionImageId
import madrileno.utils.json.JsonProtocol.*

final case class CommitUploadRequest(imageId: AuctionImageId, fileName: String) derives Decoder, Encoder.AsObject
