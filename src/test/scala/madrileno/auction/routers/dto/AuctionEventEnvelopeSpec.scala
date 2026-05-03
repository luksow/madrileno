package madrileno.auction.routers.dto

import madrileno.auction.domain.*
import madrileno.user.domain.UserId
import madrileno.utils.json.JsonProtocol.*
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.time.Instant
import java.util.UUID

class AuctionEventEnvelopeSpec extends AnyWordSpec with Matchers {

  private val auctionId = AuctionId(UUID.fromString("00000000-0000-0000-0000-000000000001"))
  private val bidId     = BidId(UUID.fromString("00000000-0000-0000-0000-000000000002"))
  private val bidderId  = UserId(UUID.fromString("00000000-0000-0000-0000-000000000003"))
  private val at        = Instant.parse("2026-01-02T03:04:05Z")
  private val endsAt    = Instant.parse("2026-02-02T03:04:05Z")

  "AuctionEventEnvelope" should {
    "wrap AuctionCreated with kind=AuctionCreated and a flat data object" in {
      val event = AuctionEvent.AuctionCreated(auctionId, WineName("Château Margaux"), Price(BigDecimal(100)), endsAt, at)
      val json  = AuctionEventEnvelope(event)

      json.hcursor.get[String]("kind") shouldBe Right("AuctionCreated")
      val data = json.hcursor.downField("data")
      data.get[AuctionId]("auctionId") shouldBe Right(auctionId)
      data.get[WineName]("wineName") shouldBe Right(WineName("Château Margaux"))
      data.get[Price]("startingPrice") shouldBe Right(Price(BigDecimal(100)))
      data.get[Instant]("endsAt") shouldBe Right(endsAt)
      data.get[Instant]("at") shouldBe Right(at)
    }

    "wrap BidPlaced — bidder identity is intentionally NOT in the wire shape (auction privacy)" in {
      val event = AuctionEvent.BidPlaced(auctionId, Price(BigDecimal(150)), at)
      val json  = AuctionEventEnvelope(event)

      json.hcursor.get[String]("kind") shouldBe Right("BidPlaced")
      val data = json.hcursor.downField("data")
      data.get[AuctionId]("auctionId") shouldBe Right(auctionId)
      data.get[Price]("amount") shouldBe Right(Price(BigDecimal(150)))
      data.keys.map(_.toSet).getOrElse(Set.empty) shouldBe Set("auctionId", "amount", "at")
    }

    "wrap AuctionCancelled with kind=AuctionCancelled" in {
      val event = AuctionEvent.AuctionCancelled(auctionId, at)
      val json  = AuctionEventEnvelope(event)

      json.hcursor.get[String]("kind") shouldBe Right("AuctionCancelled")
      val data = json.hcursor.downField("data")
      data.get[AuctionId]("auctionId") shouldBe Right(auctionId)
      data.get[Instant]("at") shouldBe Right(at)
    }

    "wrap AuctionClosed with the winning bid amount; bidder identity intentionally NOT in the wire shape" in {
      val winningBid = Bid(bidId, auctionId, bidderId, Price(BigDecimal(200)), at)
      val event      = AuctionEvent.AuctionClosed(auctionId, Some(winningBid), at)
      val json       = AuctionEventEnvelope(event)

      json.hcursor.get[String]("kind") shouldBe Right("AuctionClosed")
      val winning = json.hcursor.downField("data").downField("winningBid")
      winning.get[Price]("amount") shouldBe Right(Price(BigDecimal(200)))
      winning.keys.map(_.toSet).getOrElse(Set.empty) shouldBe Set("amount")
    }

    "wrap AuctionClosed with winningBid: null when no bids were placed" in {
      val event = AuctionEvent.AuctionClosed(auctionId, None, at)
      val json  = AuctionEventEnvelope(event)

      json.hcursor.get[String]("kind") shouldBe Right("AuctionClosed")
      json.hcursor.downField("data").downField("winningBid").focus.map(_.isNull) shouldBe Some(true)
    }
  }
}
