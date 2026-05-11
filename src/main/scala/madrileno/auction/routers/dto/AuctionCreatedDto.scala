package madrileno.auction.routers.dto

import madrileno.auction.domain.*
import madrileno.utils.json.JsonProtocol.*

import java.time.Instant
import java.util.Currency

final case class AuctionCreatedDto(
  auctionId: AuctionId,
  wineName: WineName,
  startingPrice: Price,
  currency: Currency,
  endsAt: Instant,
  createdAt: Instant)
    derives Encoder.AsObject,
      Decoder

object AuctionCreatedDto {
  def apply(event: AuctionEvent.AuctionCreated): AuctionCreatedDto = {
    import io.scalaland.chimney.dsl.*
    event.into[AuctionCreatedDto].transform
  }
}
