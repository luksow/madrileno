package madrileno.utils.featureflag.services

import madrileno.utils.featureflag.domain.*

import scala.annotation.unused

object FlagEvaluationEngine {

  final case class Result(value: FlagVariant, reason: EvaluationReason)

  def evaluate(flag: FeatureFlag, @unused context: EvaluationContext): Result =
    if (!flag.enabled) Result(flag.defaultValue, EvaluationReason.FlagDisabled)
    else Result(flag.defaultValue, EvaluationReason.Fallthrough)
}
