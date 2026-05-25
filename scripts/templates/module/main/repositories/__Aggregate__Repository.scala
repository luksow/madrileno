package __package__.__aggregate__.repositories

import __package__.__aggregate__.domain.*
import __package__.utils.db.dsl.*
import __package__.utils.db.transactor.DB
import skunk.*
import skunk.codec.all.*

import java.time.Instant

private[repositories] final case class __Aggregate__Row(
  id: __Aggregate__Id,
  name: __Aggregate__Name,
  createdAt: Instant,
  updatedAt: Instant,
  deletedAt: Option[Instant]) {
  def to__Aggregate__: __Aggregate__ = {
    import io.scalaland.chimney.dsl.*
    this.into[__Aggregate__].transform
  }

  def update(__aggregate__: __Aggregate__, now: Instant): __Aggregate__Row = {
    import io.scalaland.chimney.dsl.*
    this.patchUsing(__aggregate__).copy(updatedAt = now)
  }
}

private[repositories] object __Aggregate__Row {
  def apply(__aggregate__: __Aggregate__, now: Instant): __Aggregate__Row = {
    import io.scalaland.chimney.dsl.*
    __aggregate__
      .into[__Aggregate__Row]
      .withFieldConst(_.createdAt, now)
      .withFieldConst(_.updatedAt, now)
      .withFieldConst(_.deletedAt, None)
      .transform
  }
}

private[repositories] object __Aggregate__RowTable
    extends Table[__Aggregate__Row]("__aggregate__")
    with IdTable[__Aggregate__Row, __Aggregate__Id]
    with SoftDeleteTable {
  override val id: Column[__Aggregate__Id]        = column("id", uuid.as[__Aggregate__Id])
  val name: Column[__Aggregate__Name]             = column("name", text.as[__Aggregate__Name])
  val createdAt: Column[Instant]                  = column("created_at", timestamptz.asInstant)
  val updatedAt: Column[Instant]                  = column("updated_at", timestamptz.asInstant)
  override val deletedAt: Column[Option[Instant]] = column("deleted_at", timestamptz.asInstant.opt)

  def mapping: (List[Column[?]], Codec[__Aggregate__Row]) =
    (id, name, createdAt, updatedAt, deletedAt)
}

private[repositories] final case class __Aggregate__RowFilter(
  id: SqlPredicate[__Aggregate__Id] = p.any,
  deletedAt: SqlPredicate[Instant] = p.any)
    extends SqlFilter {
  override def filterFragment: AppliedFragment =
    SqlFilterDerivation.filterFragment(this, (__Aggregate__RowTable.id, __Aggregate__RowTable.deletedAt))
}

class __Aggregate__Repository {
  def create(__aggregate__: __Aggregate__, now: Instant): DB[__Aggregate__] = {
    val row = __Aggregate__Row(__aggregate__, now)
    repository.create(row).as(row.to__Aggregate__)
  }

  def find(id: __Aggregate__Id): DB[Option[__Aggregate__]] =
    repository.findById(id).map(_.map(_.to__Aggregate__))

  def get(id: __Aggregate__Id): DB[__Aggregate__] =
    repository.getById(id).map(_.to__Aggregate__)

  def update(
    id: __Aggregate__Id,
    f: __Aggregate__ => __Aggregate__,
    now: Instant
  ): DB[Unit] =
    repository.updateById(id, row => row.update(f(row.to__Aggregate__), now))

  def softDelete(id: __Aggregate__Id, now: Instant): DB[Unit] =
    repository.softDeleteById(id, now)

  private val repository
    : IdRepository[__Aggregate__Row, __Aggregate__Id] & SoftDeleteRepository[__Aggregate__Row, __Aggregate__Id] & FilteringRepository[__Aggregate__Row, __Aggregate__RowFilter] =
    new IdRepository[__Aggregate__Row, __Aggregate__Id](_.id) with SoftDeleteRepository[__Aggregate__Row, __Aggregate__Id]
      with FilteringRepository[__Aggregate__Row, __Aggregate__RowFilter] {
      override val table: __Aggregate__RowTable.type = __Aggregate__RowTable
    }
}
