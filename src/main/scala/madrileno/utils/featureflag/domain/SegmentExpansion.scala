package madrileno.utils.featureflag.domain

object SegmentExpansion {

  /** Replace every `SegmentMatch(name)` condition in each rule with the inline conditions of the named segment. Conditions inside a rule are AND-ed,
    * so inlining a segment's conditions joins them to the rule's other conditions. Unknown segment names produce a single `false` sentinel (an
    * unsatisfiable condition) so the rule never matches — fail-closed.
    */
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

  /** A condition that never matches — used when a referenced segment is missing, so the parent rule is short-circuited rather than silently passing.
    */
  private[domain] val UnsatisfiableSentinel: RuleCondition =
    RuleCondition.BoolEquals(AttributeName("__segment_missing__"), value = true)
}
