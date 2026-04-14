package madrileno.auction.routers.dto

import madrileno.auction.domain.Price
import madrileno.utils.json.JsonProtocol.*

case class PlaceBidRequest(amount: Price) derives Decoder, Encoder.AsObject
