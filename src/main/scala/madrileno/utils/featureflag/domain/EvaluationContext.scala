package madrileno.utils.featureflag.domain

import pl.iterators.kebs.opaque.Opaque

opaque type TargetingKey = String
object TargetingKey extends Opaque[TargetingKey, String] {
  override def validate(value: String): Either[String, TargetingKey] =
    if (value.isEmpty) Left("TargetingKey must be non-empty") else Right(value)
}

final case class EvaluationContext(targetingKey: TargetingKey, attributes: Map[String, String])

object EvaluationContext {
  def anonymous(targetingKey: TargetingKey): EvaluationContext =
    EvaluationContext(targetingKey, Map.empty)
}

enum EvaluationReason {
  case FlagDisabled, RuleMatch, PercentageRollout, Fallthrough, FlagNotFound, Error, VariantTypeMismatch
}

final case class EvaluationDetail[+T](
  value: T,
  reason: EvaluationReason,
  errorMessage: Option[String] = None)
