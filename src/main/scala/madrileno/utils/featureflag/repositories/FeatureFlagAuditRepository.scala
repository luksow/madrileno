package madrileno.utils.featureflag.repositories

import madrileno.utils.db.dsl.*
import madrileno.utils.db.transactor.DB
import madrileno.utils.featureflag.domain.*
import madrileno.utils.pagination.PageRequest
import skunk.*
import skunk.codec.all.*
import skunk.implicits.*

import java.time.Instant

import skunk.circe.codec.all.*

private[repositories] final case class AuditRow(
  id: AuditEntryId,
  flagId: Option[FlagId],
  flagKey: FlagKey,
  actor: Actor,
  action: AuditAction,
  before: Option[FeatureFlag],
  after: Option[FeatureFlag],
  createdAt: Instant) {
  def toAuditEntry: AuditEntry = {
    import io.scalaland.chimney.dsl.*
    this.into[AuditEntry].transform
  }
}

private[repositories] object AuditRow {
  def apply(entry: AuditEntry): AuditRow = {
    import io.scalaland.chimney.dsl.*
    entry.into[AuditRow].transform
  }
}

private[repositories] object AuditRowTable extends Table[AuditRow]("feature_flag_audit") with IdTable[AuditRow, AuditEntryId] {
  import FeatureFlagJsonbCodecs.given

  override val id: Column[AuditEntryId]   = column("id", uuid.as[AuditEntryId])
  val flagId: Column[Option[FlagId]]      = column("flag_id", uuid.as[FlagId].opt)
  val flagKey: Column[FlagKey]            = column("flag_key", text.as[FlagKey])
  val actor: Column[Actor]                = column("actor", text.as[Actor])
  val action: Column[AuditAction]         = column("action", text.asEnum[AuditAction])
  val before: Column[Option[FeatureFlag]] = column("before_snapshot", jsonb[FeatureFlag].opt)
  val after: Column[Option[FeatureFlag]]  = column("after_snapshot", jsonb[FeatureFlag].opt)
  val createdAt: Column[Instant]          = column("created_at", timestamptz.asInstant)

  def mapping: (List[Column[?]], Codec[AuditRow]) =
    (id, flagId, flagKey, actor, action, before, after, createdAt)
}

private[repositories] final case class AuditRowFilter(flagKey: SqlPredicate[FlagKey] = p.any, page: Option[PageRequest[AuditSortField]] = None)
    extends PageableSqlFilter[AuditSortField] {
  override def filterFragment: AppliedFragment = fromPredicates(Tuple1(flagKey -> AuditRowTable.flagKey))

  override protected def pageRequest: Option[PageRequest[AuditSortField]] = page
  override protected def tieBreakColumn: Column[?]                        = AuditRowTable.id

  override protected def sortColumnFor(field: AuditSortField): Column[?] = field match {
    case AuditSortField.CreatedAt => AuditRowTable.createdAt
  }
}

class FeatureFlagAuditRepository {
  def append(entry: AuditEntry): DB[Unit] =
    repository.create(AuditRow(entry)).void

  def listByFlagKey(key: FlagKey, page: PageRequest[AuditSortField]): DB[(List[AuditEntry], Long)] =
    repository
      .findPageByFilter(AuditRowFilter(flagKey = p.equal(key), page = Some(page)))
      .map { case (rows, total) => (rows.map(_.toAuditEntry), total) }

  def detachFlag(flagId: FlagId): DB[Unit] = { session ?=>
    session
      .execute(sql"UPDATE ${AuditRowTable.n} SET ${AuditRowTable.flagId.n} = NULL WHERE ${AuditRowTable.flagId.n} = $uuid".command)(flagId.unwrap)
      .void
  }

  private val repository: IdRepository[AuditRow, AuditEntryId] & FilteringRepository[AuditRow, AuditRowFilter] =
    new IdRepository[AuditRow, AuditEntryId](_.id) with FilteringRepository[AuditRow, AuditRowFilter] {
      override val table: AuditRowTable.type = AuditRowTable
    }
}
