package madrileno.utils.featureflag.domain

import pl.iterators.kebs.opaque.Opaque

import java.time.Instant
import java.util.UUID

opaque type RuleId = UUID
object RuleId extends Opaque[RuleId, UUID]

opaque type RulePosition = Int
object RulePosition extends Opaque[RulePosition, Int] {
  override def validate(value: Int): Either[String, RulePosition] =
    if (value >= 0) Right(value) else Left("RulePosition must be non-negative")

  given Ordering[RulePosition] = Ordering[Int].on(_.unwrap)
}

opaque type Percentage = Int
object Percentage extends Opaque[Percentage, Int] {
  override def validate(value: Int): Either[String, Percentage] =
    if (value >= 0 && value <= 100) Right(value) else Left("Percentage must be 0-100")
}

opaque type RolloutSeed = String
object RolloutSeed extends Opaque[RolloutSeed, String] {
  override def validate(value: String): Either[String, RolloutSeed] = {
    val trimmed = value.trim
    if (trimmed.isEmpty) Left("RolloutSeed must be non-empty") else Right(trimmed)
  }
}

enum RuleCondition {
  case StringEquals(attribute: AttributeName, value: String)     extends RuleCondition
  case StringIn(attribute: AttributeName, values: Set[String])   extends RuleCondition
  case StringContains(attribute: AttributeName, value: String)   extends RuleCondition
  case StringStartsWith(attribute: AttributeName, value: String) extends RuleCondition
  case IntEquals(attribute: AttributeName, value: Int)           extends RuleCondition
  case IntGreaterThan(attribute: AttributeName, value: Int)      extends RuleCondition
  case IntLessThan(attribute: AttributeName, value: Int)         extends RuleCondition
  case BoolEquals(attribute: AttributeName, value: Boolean)      extends RuleCondition
  case SegmentMatch(name: SegmentName)                           extends RuleCondition
}

enum RuleOutcome {
  case FixedValue(value: FlagVariant) extends RuleOutcome
  case PercentageRollout(
    percentage: Percentage,
    seed: RolloutSeed,
    onMatch: FlagVariant) extends RuleOutcome

  def variant: FlagVariant = this match {
    case FixedValue(value)                => value
    case PercentageRollout(_, _, onMatch) => onMatch
  }
}

final case class Rule(
  id: RuleId,
  position: RulePosition,
  description: FlagDescription,
  conditions: List[RuleCondition],
  outcome: RuleOutcome,
  createdAt: Instant)
