package madrileno.auction.routers.dto

import io.circe.Json
import io.circe.syntax.*
import madrileno.auction.domain.AuctionEvent

object AuctionEventEnvelope {
  def apply(event: AuctionEvent): Json = {
    val (kind, data) = event match {
      case e: AuctionEvent.AuctionCreated   => "AuctionCreated"   -> AuctionCreatedDto(e).asJson
      case e: AuctionEvent.BidPlaced        => "BidPlaced"        -> BidPlacedDto(e).asJson
      case e: AuctionEvent.AuctionCancelled => "AuctionCancelled" -> AuctionCancelledDto(e).asJson
      case e: AuctionEvent.AuctionClosed    => "AuctionClosed"    -> AuctionClosedDto(e).asJson
    }
    Json.obj("kind" -> kind.asJson, "data" -> data)
  }
}
