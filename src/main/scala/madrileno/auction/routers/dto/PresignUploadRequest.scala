package madrileno.auction.routers.dto

import madrileno.utils.json.JsonProtocol.*

final case class PresignUploadRequest(contentType: String, contentLength: Long) derives Decoder, Encoder.AsObject
