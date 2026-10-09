package madrileno.auction.routers.dto

import madrileno.auction.domain.Price
import madrileno.utils.json.JsonProtocol.*

final case class BidTooLowExtension(minAmount: Price) derives Encoder.AsObject, Decoder
