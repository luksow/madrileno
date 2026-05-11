package madrileno.auction.routers.dto

import madrileno.auction.domain.*
import madrileno.utils.json.JsonProtocol.*
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.time.Instant
import java.util.{Currency, UUID}

class AuctionEventEnvelopeSpec extends AnyWordSpec with Matchers {

  private val auctionId = AuctionId(UUID.fromString("00000000-0000-0000-0000-000000000001"))
  private val createdAt = Instant.parse("2026-01-02T03:04:05Z")
  private val endsAt    = Instant.parse("2026-02-02T03:04:05Z")
  private val eur       = Currency.getInstance("EUR")

  "AuctionEventEnvelope" should {
    "wrap AuctionCreated with kind=AuctionCreated and a flat data object" in {
      val event = AuctionEvent.AuctionCreated(auctionId, WineName("Château Margaux"), Price(BigDecimal(100)), eur, endsAt, createdAt)
      val json  = AuctionEventEnvelope(event)

      json.hcursor.get[String]("kind") shouldBe Right("AuctionCreated")
      val data = json.hcursor.downField("data")
      data.get[AuctionId]("auctionId") shouldBe Right(auctionId)
      data.get[WineName]("wineName") shouldBe Right(WineName("Château Margaux"))
      data.get[Price]("startingPrice") shouldBe Right(Price(BigDecimal(100)))
      data.get[Currency]("currency") shouldBe Right(eur)
      data.get[Instant]("endsAt") shouldBe Right(endsAt)
      data.get[Instant]("createdAt") shouldBe Right(createdAt)
    }

    "wrap BidPlaced — bidder identity is intentionally NOT in the wire shape (auction privacy)" in {
      val event = AuctionEvent.BidPlaced(auctionId, Price(BigDecimal(150)), eur, createdAt)
      val json  = AuctionEventEnvelope(event)

      json.hcursor.get[String]("kind") shouldBe Right("BidPlaced")
      val data = json.hcursor.downField("data")
      data.get[AuctionId]("auctionId") shouldBe Right(auctionId)
      data.get[Price]("amount") shouldBe Right(Price(BigDecimal(150)))
      data.get[Currency]("currency") shouldBe Right(eur)
      data.keys.map(_.toSet).getOrElse(Set.empty) shouldBe Set("auctionId", "amount", "currency", "createdAt")
    }

    "wrap AuctionCancelled with kind=AuctionCancelled" in {
      val event = AuctionEvent.AuctionCancelled(auctionId, createdAt)
      val json  = AuctionEventEnvelope(event)

      json.hcursor.get[String]("kind") shouldBe Right("AuctionCancelled")
      val data = json.hcursor.downField("data")
      data.get[AuctionId]("auctionId") shouldBe Right(auctionId)
      data.get[Instant]("createdAt") shouldBe Right(createdAt)
    }

    "wrap AuctionClosed with the winning bid amount + currency; bidder identity intentionally NOT in the wire shape" in {
      val event = AuctionEvent.AuctionClosed(auctionId, Some(Price(BigDecimal(200))), eur, createdAt)
      val json  = AuctionEventEnvelope(event)

      json.hcursor.get[String]("kind") shouldBe Right("AuctionClosed")
      val data = json.hcursor.downField("data")
      data.get[Price]("winningBid") shouldBe Right(Price(BigDecimal(200)))
      data.get[Currency]("currency") shouldBe Right(eur)
    }

    "wrap AuctionClosed with winningBid: null when no bids were placed" in {
      val event = AuctionEvent.AuctionClosed(auctionId, None, eur, createdAt)
      val json  = AuctionEventEnvelope(event)

      json.hcursor.get[String]("kind") shouldBe Right("AuctionClosed")
      json.hcursor.downField("data").downField("winningBid").focus.map(_.isNull) shouldBe Some(true)
    }
  }
}
