package madrileno.auction.repositories

import cats.effect.IO
import madrileno.auction.domain.*
import madrileno.user.domain.UserId
import madrileno.utils.db.dsl.*
import madrileno.utils.db.transactor.DB
import skunk.*
import skunk.codec.all.*
import skunk.implicits.*

import java.time.Instant

private[repositories] case class BidRow(
  id: BidId,
  auctionId: AuctionId,
  bidderId: UserId,
  amount: Price,
  createdAt: Instant) {
  def toBid: Bid = {
    import io.scalaland.chimney.dsl.*
    this.into[Bid].transform
  }
}

private[repositories] object BidRow {
  def apply(bid: Bid): BidRow = {
    import io.scalaland.chimney.dsl.*
    bid.into[BidRow].transform
  }
}

private[repositories] object BidRowTable extends Table[BidRow]("bid") with IdTable[BidRow, BidId] with ForeignIdTable[AuctionId] {
  override val id: Column[BidId]   = column("id", uuid.as[BidId])
  val auctionId: Column[AuctionId] = column("auction_id", uuid.as[AuctionId])
  val bidderId: Column[UserId]     = column("bidder_id", uuid.as[UserId])
  val amount: Column[Price]        = column("amount", numeric.as[Price])
  val createdAt: Column[Instant]   = column("created_at", timestamptz.asInstant)

  override val foreignId: Column[AuctionId] = auctionId

  def mapping: (List[Column[?]], Codec[BidRow]) =
    (id, auctionId, bidderId, amount, createdAt)
}

private[repositories] case class BidRowFilter(
  id: SqlPredicate[BidId] = p.any,
  auctionId: SqlPredicate[AuctionId] = p.any,
  bidderId: SqlPredicate[UserId] = p.any)
    extends SqlFilter {
  override def filterFragment: AppliedFragment =
    SqlFilterDerivation.filterFragment(this, (BidRowTable.id, BidRowTable.auctionId, BidRowTable.bidderId))
}

class BidRepository {
  def save(bid: Bid): DB[Bid] = {
    val row = BidRow(bid)
    repository.create(row).map(_.toBid)
  }

  def listByAuction(auctionId: AuctionId): DB[List[Bid]] = {
    repository.findByFilter(BidRowFilter(auctionId = p.equal(auctionId))).map(_.map(_.toBid))
  }

  def highestBid(auctionId: AuctionId): DB[Option[Bid]] = {
    val session = summon[Session[IO]]
    session
      .option(sql"""SELECT ${BidRowTable.*} FROM ${BidRowTable.n}
              WHERE ${BidRowTable.auctionId.n} = ${BidRowTable.auctionId.c}
              ORDER BY ${BidRowTable.amount.n} DESC
              LIMIT 1""".query(BidRowTable.c))(auctionId)
      .map(_.map(_.toBid))
  }

  def countByAuction(auctionId: AuctionId): DB[Long] = {
    repository.countByFilter(BidRowFilter(auctionId = p.equal(auctionId)))
  }

  private val repository: IdRepository[BidRow, BidId] & ForeignIdRepository[BidRow, AuctionId] & FilteringRepository[BidRow, BidRowFilter] =
    new IdRepository[BidRow, BidId](_.id) with ForeignIdRepository[BidRow, AuctionId] with FilteringRepository[BidRow, BidRowFilter] {
      override val table: BidRowTable.type = BidRowTable
    }
}
