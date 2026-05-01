package madrileno.auction.routers.dto

import madrileno.auction.domain.*
import madrileno.utils.json.JsonProtocol.*

import java.time.Instant

case class AuctionClosedDto(
  auctionId: AuctionId,
  winningBid: Option[Price],
  at: Instant)
    derives Encoder.AsObject,
      Decoder

object AuctionClosedDto {
  def apply(event: AuctionEvent.AuctionClosed): AuctionClosedDto = {
    import io.scalaland.chimney.dsl.*
    event.into[AuctionClosedDto].transform
  }
}
