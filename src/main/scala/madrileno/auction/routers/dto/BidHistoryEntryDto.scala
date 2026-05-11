package madrileno.auction.routers.dto

import madrileno.auction.domain.*
import madrileno.utils.json.JsonProtocol.*

import java.time.Instant
import java.util.Currency

case class BidHistoryEntryDto(
  amount: Price,
  currency: Currency,
  bidderRef: BidderRef,
  createdAt: Instant)
    derives Encoder.AsObject,
      Decoder

object BidHistoryEntryDto {
  def apply(entry: BidHistoryEntry): BidHistoryEntryDto = {
    import io.scalaland.chimney.dsl.*
    entry.into[BidHistoryEntryDto].transform
  }
}
