package madrileno.utils.featureflag.domain

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.time.Instant
import java.util.UUID

class SegmentExpansionSpec extends AnyWordSpec with Matchers {

  private val now = Instant.parse("2026-06-07T00:00:00Z")

  private def rule(conditions: List[RuleCondition]): Rule = Rule(
    id = RuleId(UUID.randomUUID()),
    position = RulePosition(0),
    description = FlagDescription(""),
    conditions = conditions,
    outcome = RuleOutcome.FixedValue(FlagVariant.BoolVariant(true)),
    createdAt = now
  )

  private def segment(name: String, conditions: List[RuleCondition]): Segment = Segment(
    id = SegmentId(UUID.randomUUID()),
    name = SegmentName(name),
    description = FlagDescription(""),
    conditions = conditions,
    createdAt = now,
    updatedAt = now
  )

  "SegmentExpansion.expand" should {
    "inline a segment's conditions in place of a SegmentMatch" in {
      val s = segment(
        "enterprise-eu",
        List(RuleCondition.StringEquals(AttributeName("plan"), "enterprise"), RuleCondition.StringIn(AttributeName("region"), Set("eu")))
      )
      val r        = rule(List(RuleCondition.SegmentMatch(SegmentName("enterprise-eu"))))
      val expanded = SegmentExpansion.expand(List(r), List(s))
      expanded.head.conditions shouldBe s.conditions
    }

    "preserve other conditions and inline segment conditions alongside them" in {
      val s = segment("eu", List(RuleCondition.StringIn(AttributeName("region"), Set("eu"))))
      val r = rule(
        List(
          RuleCondition.StringEquals(AttributeName("plan"), "enterprise"),
          RuleCondition.SegmentMatch(SegmentName("eu")),
          RuleCondition.BoolEquals(AttributeName("verified"), value = true)
        )
      )
      val expanded = SegmentExpansion.expand(List(r), List(s))
      expanded.head.conditions shouldBe List(
        RuleCondition.StringEquals(AttributeName("plan"), "enterprise"),
        RuleCondition.StringIn(AttributeName("region"), Set("eu")),
        RuleCondition.BoolEquals(AttributeName("verified"), value = true)
      )
    }

    "substitute a sentinel for an unknown segment so the rule short-circuits" in {
      val r        = rule(List(RuleCondition.SegmentMatch(SegmentName("missing-segment"))))
      val expanded = SegmentExpansion.expand(List(r), Nil)
      expanded.head.conditions should have size 1
      expanded.head.conditions.head should not be a[RuleCondition.SegmentMatch]
    }

    "leave rules without SegmentMatch untouched" in {
      val r        = rule(List(RuleCondition.StringEquals(AttributeName("plan"), "enterprise")))
      val expanded = SegmentExpansion.expand(List(r), Nil)
      expanded shouldBe List(r)
    }
  }
}
