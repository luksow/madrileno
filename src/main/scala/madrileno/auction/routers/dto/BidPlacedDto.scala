package madrileno.auction.routers.dto

import madrileno.auction.domain.*
import madrileno.utils.json.JsonProtocol.*

import java.time.Instant
import java.util.Currency

case class BidPlacedDto(
  auctionId: AuctionId,
  amount: Price,
  currency: Currency,
  at: Instant)
    derives Encoder.AsObject,
      Decoder

object BidPlacedDto {
  def apply(event: AuctionEvent.BidPlaced): BidPlacedDto = {
    import io.scalaland.chimney.dsl.*
    event.into[BidPlacedDto].transform
  }
}
