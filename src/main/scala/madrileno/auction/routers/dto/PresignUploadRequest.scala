package madrileno.auction.routers.dto

import madrileno.utils.json.JsonProtocol.*

case class PresignUploadRequest(
  fileName: String,
  contentType: String,
  contentLength: Long)
    derives Decoder,
      Encoder.AsObject
