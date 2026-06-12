package madrileno.utils.featureflag.repositories

import madrileno.utils.db.dsl.*
import madrileno.utils.db.transactor.DB
import madrileno.utils.featureflag.domain.*
import skunk.*
import skunk.circe.codec.all.*
import skunk.codec.all.*

import java.time.Instant

private[repositories] final case class SegmentRow(
  id: SegmentId,
  name: SegmentName,
  description: FlagDescription,
  conditions: List[RuleCondition],
  createdAt: Instant,
  updatedAt: Instant) {
  def toSegment: Segment = {
    import io.scalaland.chimney.dsl.*
    this.into[Segment].transform
  }
}

private[repositories] object SegmentRow {
  def apply(segment: Segment): SegmentRow = {
    import io.scalaland.chimney.dsl.*
    segment.into[SegmentRow].transform
  }
}

private[repositories] object SegmentRowTable extends Table[SegmentRow]("feature_flag_segment") with IdTable[SegmentRow, SegmentId] {
  import FeatureFlagJsonbCodecs.given

  override val id: Column[SegmentId]          = column("id", uuid.as[SegmentId])
  val name: Column[SegmentName]               = column("name", text.as[SegmentName])
  val description: Column[FlagDescription]    = column("description", text.as[FlagDescription])
  val conditions: Column[List[RuleCondition]] = column("conditions", jsonb[List[RuleCondition]])
  val createdAt: Column[Instant]              = column("created_at", timestamptz.asInstant)
  val updatedAt: Column[Instant]              = column("updated_at", timestamptz.asInstant)

  def mapping: (List[Column[?]], Codec[SegmentRow]) =
    (id, name, description, conditions, createdAt, updatedAt)
}

class SegmentRepository {
  def findAll: DB[List[Segment]] =
    repository.findByFilter(SegmentRowFilter()).map(_.map(_.toSegment))

  def findByName(name: SegmentName): DB[Option[Segment]] =
    repository.findOneByFilter(SegmentRowFilter(name = p.equal(name))).map(_.map(_.toSegment))

  def save(segment: Segment): DB[Unit] =
    repository.upsert(SegmentRow(segment))

  def deleteById(id: SegmentId): DB[Unit] =
    repository.deleteById(id)

  private val repository: IdRepository[SegmentRow, SegmentId] & FilteringRepository[SegmentRow, SegmentRowFilter] =
    new IdRepository[SegmentRow, SegmentId](_.id) with FilteringRepository[SegmentRow, SegmentRowFilter] {
      override val table: SegmentRowTable.type = SegmentRowTable
    }
}

private[repositories] final case class SegmentRowFilter(id: SqlPredicate[SegmentId] = p.any, name: SqlPredicate[SegmentName] = p.any)
    extends SqlFilter {
  override def filterFragment: AppliedFragment =
    SqlFilterDerivation.filterFragment(this, (SegmentRowTable.id, SegmentRowTable.name))
}
