package madrileno.auction.repositories

import cats.effect.IO
import cats.syntax.all.*
import madrileno.auction.domain.*
import madrileno.utils.db.dsl.*
import madrileno.utils.db.transactor.{DB, DBInTransaction}
import madrileno.utils.storage.StorageKey
import org.http4s.Header
import org.http4s.headers.`Content-Type`
import skunk.*
import skunk.codec.all.*
import skunk.implicits.*

import java.time.Instant

private[repositories] case class AuctionImageRow(
  id: AuctionImageId,
  auctionId: AuctionId,
  storageKey: StorageKey,
  fileName: String,
  contentType: `Content-Type`,
  sizeBytes: SizeBytes,
  position: ImagePosition,
  uploadedAt: Instant,
  deletedAt: Option[Instant]) {
  def toAuctionImage: AuctionImage = {
    import io.scalaland.chimney.dsl.*
    this.into[AuctionImage].transform
  }
}

private[repositories] object AuctionImageRow {
  def apply(image: AuctionImage): AuctionImageRow = {
    import io.scalaland.chimney.dsl.*
    image.into[AuctionImageRow].transform
  }
}

private[repositories] object AuctionImageRowTable
    extends Table[AuctionImageRow]("auction_image")
    with IdTable[AuctionImageRow, AuctionImageId]
    with SoftDeleteTable
    with ForeignIdTable[AuctionId] {
  private val contentTypeCodec: Codec[`Content-Type`] = text.imap { s =>
    `Content-Type`.parse(s).getOrElse(throw new IllegalStateException(s"Invalid content-type in DB: $s"))
  }(Header[`Content-Type`].value)

  override val id: Column[AuctionImageId]         = column("id", uuid.as[AuctionImageId])
  val auctionId: Column[AuctionId]                = column("auction_id", uuid.as[AuctionId])
  val storageKey: Column[StorageKey]              = column("storage_key", text.as[StorageKey])
  val fileName: Column[String]                    = column("file_name", text)
  val contentType: Column[`Content-Type`]         = column("content_type", contentTypeCodec)
  val sizeBytes: Column[SizeBytes]                = column("size_bytes", int8.as[SizeBytes])
  val position: Column[ImagePosition]             = column("position", int4.as[ImagePosition])
  val uploadedAt: Column[Instant]                 = column("uploaded_at", timestamptz.asInstant)
  override val deletedAt: Column[Option[Instant]] = column("deleted_at", timestamptz.asInstant.opt)

  override val foreignId: Column[AuctionId] = auctionId

  def mapping: (List[Column[?]], Codec[AuctionImageRow]) =
    (id, auctionId, storageKey, fileName, contentType, sizeBytes, position, uploadedAt, deletedAt)
}

private[repositories] case class AuctionImageRowFilter(
  id: SqlPredicate[AuctionImageId] = p.any,
  auctionId: SqlPredicate[AuctionId] = p.any,
  deletedAt: SqlPredicate[Instant] = p.isNull)
    extends SqlFilter {
  override def filterFragment: AppliedFragment =
    SqlFilterDerivation.filterFragment(this, (AuctionImageRowTable.id, AuctionImageRowTable.auctionId, AuctionImageRowTable.deletedAt))

  override def orderByFragment: Fragment[Void] = fromSqlOrderBy(new SqlOrderBy {
    override val fragment: Fragment[Void] = sql"${AuctionImageRowTable.position.n} ASC"
  })
}

class AuctionImageRepository {
  def save(image: AuctionImage): DB[Unit] =
    repository.create(AuctionImageRow(image)).void

  def find(id: AuctionImageId): DB[Option[AuctionImage]] =
    repository.findById(id).map(_.map(_.toAuctionImage))

  def listByAuction(auctionId: AuctionId): DB[List[AuctionImage]] =
    repository.findByFilter(AuctionImageRowFilter(auctionId = p.equal(auctionId))).map(_.map(_.toAuctionImage))

  def listByAuctionForUpdate(auctionId: AuctionId): DBInTransaction[List[AuctionImage]] =
    repository.findByFilter(AuctionImageRowFilter(auctionId = p.equal(auctionId)), Lock.ForUpdate).map(_.map(_.toAuctionImage))

  def nextPosition(auctionId: AuctionId): DB[Int] = {
    val table = AuctionImageRowTable
    val query = sql"""SELECT COALESCE(MAX(${table.position.n}) + 1, 0)
                      FROM ${table.n}
                      WHERE ${table.auctionId.n} = ${table.auctionId.c}
                        AND ${table.deletedAt.n} IS NULL""".query(int4)
    summon[Session[IO]].unique(query)(auctionId)
  }

  def softDelete(id: AuctionImageId, now: Instant): DB[Unit] =
    repository.softDeleteById(id, now)

  def bulkSetPositions(updates: List[(AuctionImageId, ImagePosition)]): DBInTransaction[Unit] = {
    val table = AuctionImageRowTable
    val command = sql"""UPDATE ${table.n} SET ${table.position.n} = $int4
                        WHERE ${table.id.n} = ${table.id.c}""".command
    val session = summon[Session[IO]]
    val phase1  = updates.zipWithIndex.traverse_ { case ((id, _), idx) => session.execute(command)((-(idx + 1), id)).void }
    val phase2  = updates.traverse_ { case (id, pos) => session.execute(command)((pos.unwrap, id)).void }
    phase1 >> phase2
  }

  private val repository: IdRepository[AuctionImageRow, AuctionImageId] & SoftDeleteRepository[AuctionImageRow, AuctionImageId] & ForeignIdRepository[
    AuctionImageRow,
    AuctionId
  ] & FilteringRepository[AuctionImageRow, AuctionImageRowFilter] =
    new IdRepository[AuctionImageRow, AuctionImageId](_.id)
      with SoftDeleteRepository[AuctionImageRow, AuctionImageId]
      with ForeignIdRepository[AuctionImageRow, AuctionId]
      with FilteringRepository[AuctionImageRow, AuctionImageRowFilter] {
      override val table: AuctionImageRowTable.type = AuctionImageRowTable
    }
}
