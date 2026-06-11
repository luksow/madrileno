package madrileno.utils.featureflag.domain

object SegmentExpansion {

  def expand(rules: List[Rule], segments: List[Segment]): List[Rule] = {
    val byName = segments.map(s => s.name -> s).toMap
    rules.map(expandRule(_, byName))
  }

  private def expandRule(rule: Rule, byName: Map[SegmentName, Segment]): Rule =
    rule.copy(conditions = rule.conditions.flatMap(expandCondition(_, byName)))

  private def expandCondition(condition: RuleCondition, byName: Map[SegmentName, Segment]): List[RuleCondition] = condition match {
    case RuleCondition.SegmentMatch(name) =>
      byName.get(name) match {
        case Some(segment) => segment.conditions
        case None          => List(UnsatisfiableSentinel)
      }
    case other => List(other)
  }

  private[domain] val UnsatisfiableSentinel: RuleCondition =
    RuleCondition.BoolEquals(AttributeName("__segment_missing__"), value = true)
}
