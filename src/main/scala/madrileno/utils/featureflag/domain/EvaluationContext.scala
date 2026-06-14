package madrileno.utils.featureflag.domain

import pl.iterators.kebs.opaque.Opaque

opaque type TargetingKey = String
object TargetingKey extends Opaque[TargetingKey, String] {
  override def validate(value: String): Either[String, TargetingKey] = {
    val trimmed = value.trim
    if (trimmed.isEmpty) Left("TargetingKey must be non-empty") else Right(trimmed)
  }
}

opaque type AttributeName = String
object AttributeName extends Opaque[AttributeName, String] {
  override def validate(value: String): Either[String, AttributeName] = {
    val trimmed = value.trim
    if (trimmed.isEmpty) Left("AttributeName must be non-empty") else Right(trimmed)
  }
}

opaque type AttributeValue = String
object AttributeValue extends Opaque[AttributeValue, String] {
  override def validate(value: String): Either[String, AttributeValue] = Right(value.trim)
}

final case class EvaluationContext(targetingKey: TargetingKey, attributes: Map[AttributeName, AttributeValue])

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
