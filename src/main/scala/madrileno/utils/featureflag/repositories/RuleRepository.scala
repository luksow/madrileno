package madrileno.utils.featureflag.repositories

import madrileno.utils.db.dsl.*
import madrileno.utils.db.transactor.DB
import madrileno.utils.featureflag.domain.*
import skunk.*
import skunk.circe.codec.all.*
import skunk.codec.all.*

import java.time.Instant

private[repositories] final case class RuleRow(
  id: RuleId,
  flagId: FlagId,
  position: RulePosition,
  description: FlagDescription,
  conditions: List[RuleCondition],
  outcome: RuleOutcome,
  createdAt: Instant) {
  def toRule: Rule = {
    import io.scalaland.chimney.dsl.*
    this.into[Rule].transform
  }
}

private[repositories] object RuleRow {
  def apply(flagId: FlagId, rule: Rule): RuleRow = {
    import io.scalaland.chimney.dsl.*
    rule.into[RuleRow].withFieldConst(_.flagId, flagId).transform
  }
}

private[repositories] object RuleRowTable extends Table[RuleRow]("feature_flag_rule") with IdTable[RuleRow, RuleId] with ForeignIdTable[FlagId] {
  override val id: Column[RuleId]             = column("id", uuid.as[RuleId])
  val flagId: Column[FlagId]                  = column("flag_id", uuid.as[FlagId])
  val position: Column[RulePosition]          = column("position", int4.as[RulePosition])
  val description: Column[FlagDescription]    = column("description", text.as[FlagDescription])
  val conditions: Column[List[RuleCondition]] = column("conditions", jsonb[List[RuleCondition]])
  val outcome: Column[RuleOutcome]            = column("outcome", jsonb[RuleOutcome])
  val createdAt: Column[Instant]              = column("created_at", timestamptz.asInstant)

  override val foreignId: Column[FlagId] = flagId

  def mapping: (List[Column[?]], Codec[RuleRow]) =
    (id, flagId, position, description, conditions, outcome, createdAt)
}

class RuleRepository {
  def findByFlagId(flagId: FlagId): DB[List[Rule]] =
    repository.findByForeignId(flagId).map(_.map(_.toRule).sortBy(_.position))

  def save(flagId: FlagId, rule: Rule): DB[Unit] =
    repository.create(RuleRow(flagId, rule)).void

  private val repository: IdRepository[RuleRow, RuleId] & ForeignIdRepository[RuleRow, FlagId] =
    new IdRepository[RuleRow, RuleId](_.id) with ForeignIdRepository[RuleRow, FlagId] {
      override val table: RuleRowTable.type = RuleRowTable
    }
}
