package madrileno.auction.repositories

import cats.effect.IO
import madrileno.auction.domain.*
import madrileno.utils.db.dsl.*
import madrileno.utils.db.transactor.DB
import madrileno.utils.storage.StorageKey
import skunk.*
import skunk.codec.all.*
import skunk.implicits.*

import java.time.Instant

private[repositories] case class AuctionImageRow(
  id: AuctionImageId,
  auctionId: AuctionId,
  storageKey: StorageKey,
  contentType: ContentType,
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
  override val id: Column[AuctionImageId]         = column("id", uuid.as[AuctionImageId])
  val auctionId: Column[AuctionId]                = column("auction_id", uuid.as[AuctionId])
  val storageKey: Column[StorageKey]              = column("storage_key", text.as[StorageKey])
  val contentType: Column[ContentType]            = column("content_type", text.as[ContentType])
  val sizeBytes: Column[SizeBytes]                = column("size_bytes", int8.as[SizeBytes])
  val position: Column[ImagePosition]             = column("position", int4.as[ImagePosition])
  val uploadedAt: Column[Instant]                 = column("uploaded_at", timestamptz.asInstant)
  override val deletedAt: Column[Option[Instant]] = column("deleted_at", timestamptz.asInstant.opt)

  override val foreignId: Column[AuctionId] = auctionId

  def mapping: (List[Column[?]], Codec[AuctionImageRow]) =
    (id, auctionId, storageKey, contentType, sizeBytes, position, uploadedAt, deletedAt)
}

private[repositories] case class AuctionImageRowFilter(
  id: SqlPredicate[AuctionImageId] = p.any,
  auctionId: SqlPredicate[AuctionId] = p.any,
  deletedAt: SqlPredicate[Instant] = p.isNull)
    extends SqlFilter {
  override def filterFragment: AppliedFragment =
    SqlFilterDerivation.filterFragment(this, (AuctionImageRowTable.id, AuctionImageRowTable.auctionId, AuctionImageRowTable.deletedAt))
}

class AuctionImageRepository {
  def save(image: AuctionImage): DB[Unit] =
    repository.create(AuctionImageRow(image)).void

  def find(id: AuctionImageId): DB[Option[AuctionImage]] =
    repository.findById(id).map(_.map(_.toAuctionImage))

  def listByAuction(auctionId: AuctionId): DB[List[AuctionImage]] = {
    val table = AuctionImageRowTable
    val query = sql"""SELECT ${table.*} FROM ${table.n}
                      WHERE ${table.auctionId.n} = ${table.auctionId.c}
                        AND ${table.deletedAt.n} IS NULL
                      ORDER BY ${table.position.n} ASC""".query(table.c)
    summon[Session[IO]].execute(query)(auctionId).map(_.map(_.toAuctionImage))
  }

  def nextPosition(auctionId: AuctionId): DB[Int] = {
    val table = AuctionImageRowTable
    val query = sql"""SELECT COALESCE(MAX(${table.position.n}) + 1, 0)
                      FROM ${table.n}
                      WHERE ${table.auctionId.n} = ${table.auctionId.c}
                        AND ${table.deletedAt.n} IS NULL""".query(int4)
    summon[Session[IO]].unique(query)(auctionId)
  }

  def setPosition(id: AuctionImageId, position: ImagePosition): DB[Unit] = {
    val table = AuctionImageRowTable
    val command = sql"""UPDATE ${table.n} SET ${table.position.n} = ${table.position.c}
                        WHERE ${table.id.n} = ${table.id.c}""".command
    summon[Session[IO]].execute(command)((position, id)).void
  }

  def softDelete(id: AuctionImageId, now: Instant): DB[Unit] =
    repository.softDeleteById(id, now)

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
