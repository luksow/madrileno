package madrileno.utils.featureflag.services

import madrileno.utils.featureflag.domain.*
import madrileno.utils.featureflag.domain.RuleCondition.*
import madrileno.utils.featureflag.domain.RuleOutcome.*

import scala.util.hashing.MurmurHash3

object FlagEvaluationEngine {

  final case class Result(value: FlagVariant, reason: EvaluationReason)

  def evaluate(
    flag: FeatureFlag,
    segments: Map[SegmentName, Segment],
    context: EvaluationContext
  ): Result =
    if (!flag.enabled) Result(flag.defaultValue, EvaluationReason.FlagDisabled)
    else firstMatch(flag.rules, segments, context).getOrElse(Result(flag.defaultValue, EvaluationReason.Fallthrough))

  private def firstMatch(
    rules: List[Rule],
    segments: Map[SegmentName, Segment],
    context: EvaluationContext
  ): Option[Result] =
    rules.sortBy(_.position).iterator.flatMap(applyRule(_, segments, context)).nextOption()

  private def applyRule(
    rule: Rule,
    segments: Map[SegmentName, Segment],
    context: EvaluationContext
  ): Option[Result] =
    if (rule.conditions.forall(matches(_, segments, context, visitedSegments = Set.empty))) applyOutcome(rule.outcome, context) else None

  private def matches(
    condition: RuleCondition,
    segments: Map[SegmentName, Segment],
    context: EvaluationContext,
    visitedSegments: Set[SegmentName]
  ): Boolean = condition match {
    case StringEquals(attr, v)     => attr.lookup(context).exists(_ == v)
    case StringIn(attr, vs)        => attr.lookup(context).exists(vs.contains)
    case StringContains(attr, v)   => attr.lookup(context).exists(_.contains(v))
    case StringStartsWith(attr, v) => attr.lookup(context).exists(_.startsWith(v))
    case IntEquals(attr, v)        => attr.lookup(context).flatMap(_.toIntOption).exists(_ == v)
    case IntGreaterThan(attr, v)   => attr.lookup(context).flatMap(_.toIntOption).exists(_ > v)
    case IntLessThan(attr, v)      => attr.lookup(context).flatMap(_.toIntOption).exists(_ < v)
    case BoolEquals(attr, v)       => attr.lookup(context).flatMap(_.toBooleanOption).exists(_ == v)
    case SegmentMatch(name) =>
      !visitedSegments(name) && segments.get(name).exists(_.conditions.forall(matches(_, segments, context, visitedSegments + name)))
  }

  private def applyOutcome(outcome: RuleOutcome, context: EvaluationContext): Option[Result] = outcome match {
    case FixedValue(value) => Some(Result(value, EvaluationReason.RuleMatch))
    case PercentageRollout(percentage, seed, onMatch) =>
      if (inBucket(seed, context.targetingKey, percentage)) Some(Result(onMatch, EvaluationReason.PercentageRollout))
      else None
  }

  private def inBucket(
    seed: RolloutSeed,
    targetingKey: TargetingKey,
    pct: Percentage
  ): Boolean =
    bucketOf(s"${seed.unwrap}:${targetingKey.unwrap}") < pct.unwrap

  private[services] def bucketOf(input: String): Int =
    Math.floorMod(MurmurHash3.stringHash(input), 100)

  extension (attr: AttributeName) {
    private def lookup(context: EvaluationContext): Option[String] = context.attributes.get(attr).map(_.unwrap)
  }
}
