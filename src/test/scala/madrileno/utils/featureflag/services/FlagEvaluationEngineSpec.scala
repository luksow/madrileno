package madrileno.utils.featureflag.services

import madrileno.utils.featureflag.domain.*
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.time.Instant
import java.util.UUID

class FlagEvaluationEngineSpec extends AnyWordSpec with Matchers {

  private val now = Instant.parse("2026-06-07T00:00:00Z")

  private def flag(
    enabled: Boolean,
    defaultValue: FlagVariant,
    rules: List[Rule] = Nil
  ): FeatureFlag = FeatureFlag(
    id = FlagId(UUID.randomUUID()),
    key = FlagKey("test-flag"),
    description = FlagDescription(""),
    enabled = enabled,
    defaultValue = defaultValue,
    clientExposed = false,
    rules = rules,
    createdAt = now,
    updatedAt = now
  )

  private def rule(
    position: Int,
    conditions: List[RuleCondition],
    outcome: RuleOutcome
  ): Rule = Rule(
    id = RuleId(UUID.randomUUID()),
    position = RulePosition(position),
    description = FlagDescription(""),
    conditions = conditions,
    outcome = outcome,
    createdAt = now
  )

  private val anon = EvaluationContext.anonymous(TargetingKey("user-1"))

  private def ctxWith(attrs: (String, String)*): EvaluationContext =
    EvaluationContext(TargetingKey("user-1"), attrs.map { case (k, v) => AttributeName(k) -> AttributeValue(v) }.toMap)

  "FlagEvaluationEngine.evaluate" should {
    "return defaultValue with Fallthrough reason when the flag is enabled and no rules" in {
      val f      = flag(enabled = true, FlagVariant.BoolVariant(true))
      val result = FlagEvaluationEngine.evaluate(f, anon)
      result.value shouldBe FlagVariant.BoolVariant(true)
      result.reason shouldBe EvaluationReason.Fallthrough
    }

    "return defaultValue with FlagDisabled reason when the flag is disabled (even with rules)" in {
      val matching =
        rule(0, List(RuleCondition.StringEquals(AttributeName("plan"), "enterprise")), RuleOutcome.FixedValue(FlagVariant.BoolVariant(true)))
      val f      = flag(enabled = false, FlagVariant.BoolVariant(false), rules = List(matching))
      val result = FlagEvaluationEngine.evaluate(f, ctxWith("plan" -> "enterprise"))
      result.value shouldBe FlagVariant.BoolVariant(false)
      result.reason shouldBe EvaluationReason.FlagDisabled
    }

    "preserve the variant for all four types" in {
      val cases: List[FlagVariant] = List(
        FlagVariant.BoolVariant(true),
        FlagVariant.StringVariant("hello"),
        FlagVariant.IntVariant(42),
        FlagVariant.JsonVariant(io.circe.Json.obj("k" -> io.circe.Json.fromString("v")))
      )
      cases.foreach { value =>
        val f      = flag(enabled = true, value)
        val result = FlagEvaluationEngine.evaluate(f, anon)
        result.value shouldBe value
      }
    }
  }

  "rule conditions" should {
    val onValue = FlagVariant.BoolVariant(true)
    def withCondition(c: RuleCondition): FeatureFlag =
      flag(enabled = true, FlagVariant.BoolVariant(false), rules = List(rule(0, List(c), RuleOutcome.FixedValue(onValue))))

    "StringEquals matches exact" in {
      val f = withCondition(RuleCondition.StringEquals(AttributeName("plan"), "enterprise"))
      FlagEvaluationEngine.evaluate(f, ctxWith("plan" -> "enterprise")).reason shouldBe EvaluationReason.RuleMatch
      FlagEvaluationEngine.evaluate(f, ctxWith("plan" -> "free")).reason shouldBe EvaluationReason.Fallthrough
    }

    "StringIn matches set membership" in {
      val f = withCondition(RuleCondition.StringIn(AttributeName("region"), Set("eu-west-1", "eu-west-2")))
      FlagEvaluationEngine.evaluate(f, ctxWith("region" -> "eu-west-2")).reason shouldBe EvaluationReason.RuleMatch
      FlagEvaluationEngine.evaluate(f, ctxWith("region" -> "us-east-1")).reason shouldBe EvaluationReason.Fallthrough
    }

    "StringContains and StringStartsWith match substrings" in {
      val containsF   = withCondition(RuleCondition.StringContains(AttributeName("ua"), "Firefox"))
      val startsWithF = withCondition(RuleCondition.StringStartsWith(AttributeName("ua"), "Mozilla"))
      FlagEvaluationEngine.evaluate(containsF, ctxWith("ua" -> "Mozilla/5.0 Firefox/120")).reason shouldBe EvaluationReason.RuleMatch
      FlagEvaluationEngine.evaluate(containsF, ctxWith("ua" -> "Mozilla/5.0 Chrome/100")).reason shouldBe EvaluationReason.Fallthrough
      FlagEvaluationEngine.evaluate(startsWithF, ctxWith("ua" -> "Mozilla/5.0 Firefox/120")).reason shouldBe EvaluationReason.RuleMatch
    }

    "Int operators parse the attribute string and compare" in {
      val eqF = withCondition(RuleCondition.IntEquals(AttributeName("age"), 18))
      val gtF = withCondition(RuleCondition.IntGreaterThan(AttributeName("age"), 21))
      val ltF = withCondition(RuleCondition.IntLessThan(AttributeName("age"), 65))
      FlagEvaluationEngine.evaluate(eqF, ctxWith("age" -> "18")).reason shouldBe EvaluationReason.RuleMatch
      FlagEvaluationEngine.evaluate(gtF, ctxWith("age" -> "30")).reason shouldBe EvaluationReason.RuleMatch
      FlagEvaluationEngine.evaluate(gtF, ctxWith("age" -> "15")).reason shouldBe EvaluationReason.Fallthrough
      FlagEvaluationEngine.evaluate(ltF, ctxWith("age" -> "100")).reason shouldBe EvaluationReason.Fallthrough
    }

    "BoolEquals parses the attribute string" in {
      val f = withCondition(RuleCondition.BoolEquals(AttributeName("verified"), true))
      FlagEvaluationEngine.evaluate(f, ctxWith("verified" -> "true")).reason shouldBe EvaluationReason.RuleMatch
      FlagEvaluationEngine.evaluate(f, ctxWith("verified" -> "false")).reason shouldBe EvaluationReason.Fallthrough
    }

    "all conditions in a rule must match (AND within rule)" in {
      val f = flag(
        enabled = true,
        defaultValue = FlagVariant.BoolVariant(false),
        rules = List(
          rule(
            0,
            List(RuleCondition.StringEquals(AttributeName("plan"), "enterprise"), RuleCondition.StringIn(AttributeName("region"), Set("eu-west-1"))),
            RuleOutcome.FixedValue(FlagVariant.BoolVariant(true))
          )
        )
      )
      FlagEvaluationEngine.evaluate(f, ctxWith("plan" -> "enterprise", "region" -> "eu-west-1")).reason shouldBe EvaluationReason.RuleMatch
      FlagEvaluationEngine.evaluate(f, ctxWith("plan" -> "enterprise", "region" -> "us-east-1")).reason shouldBe EvaluationReason.Fallthrough
      FlagEvaluationEngine.evaluate(f, ctxWith("plan" -> "free", "region" -> "eu-west-1")).reason shouldBe EvaluationReason.Fallthrough
    }

    "first matching rule wins (rules evaluated in position order)" in {
      val ruleA =
        rule(0, List(RuleCondition.StringEquals(AttributeName("plan"), "enterprise")), RuleOutcome.FixedValue(FlagVariant.StringVariant("variant-a")))
      val ruleB =
        rule(1, List(RuleCondition.StringEquals(AttributeName("plan"), "enterprise")), RuleOutcome.FixedValue(FlagVariant.StringVariant("variant-b")))
      val f = flag(enabled = true, FlagVariant.StringVariant("default"), rules = List(ruleA, ruleB))
      val r = FlagEvaluationEngine.evaluate(f, ctxWith("plan" -> "enterprise"))
      r.value shouldBe FlagVariant.StringVariant("variant-a") // first rule wins
    }

    "SegmentMatch never matches at engine level (it should be expanded earlier)" in {
      val f = withCondition(RuleCondition.SegmentMatch(SegmentName("does-not-exist")))
      FlagEvaluationEngine.evaluate(f, anon).reason shouldBe EvaluationReason.Fallthrough
    }
  }

  "PercentageRollout" should {
    "return PercentageRollout reason for users in the bucket; Fallthrough otherwise" in {
      val seed    = RolloutSeed("phase2-test")
      val on      = FlagVariant.BoolVariant(true)
      val r       = rule(0, Nil, RuleOutcome.PercentageRollout(Percentage(50), seed, on))
      val f       = flag(enabled = true, FlagVariant.BoolVariant(false), rules = List(r))
      val sample  = (1 to 200).map(i => ctxWith().copy(targetingKey = TargetingKey(s"user-$i")))
      val results = sample.map(c => FlagEvaluationEngine.evaluate(f, c).reason)
      val hits    = results.count(_ == EvaluationReason.PercentageRollout)
      // With a 50% rollout and 200 stable hashes, the count should land near 100. Wide tolerance.
      hits should (be >= 70 and be <= 130)
    }

    "be sticky — same user, same seed, same bucket — across invocations" in {
      val seed = RolloutSeed("phase2-sticky")
      val on   = FlagVariant.BoolVariant(true)
      val r    = rule(0, Nil, RuleOutcome.PercentageRollout(Percentage(50), seed, on))
      val f    = flag(enabled = true, FlagVariant.BoolVariant(false), rules = List(r))
      val ctx1 = ctxWith().copy(targetingKey = TargetingKey("alice"))
      val r1   = FlagEvaluationEngine.evaluate(f, ctx1).reason
      val r2   = FlagEvaluationEngine.evaluate(f, ctx1).reason
      val r3   = FlagEvaluationEngine.evaluate(f, ctx1).reason
      r1 shouldBe r2
      r2 shouldBe r3
    }

    "Percentage(0) never matches; Percentage(100) always matches" in {
      val zero   = rule(0, Nil, RuleOutcome.PercentageRollout(Percentage(0), RolloutSeed("x"), FlagVariant.BoolVariant(true)))
      val full   = rule(0, Nil, RuleOutcome.PercentageRollout(Percentage(100), RolloutSeed("x"), FlagVariant.BoolVariant(true)))
      val fZero  = flag(enabled = true, FlagVariant.BoolVariant(false), rules = List(zero))
      val fFull  = flag(enabled = true, FlagVariant.BoolVariant(false), rules = List(full))
      val sample = (1 to 50).map(i => ctxWith().copy(targetingKey = TargetingKey(s"u-$i")))
      sample.foreach { c =>
        FlagEvaluationEngine.evaluate(fZero, c).reason shouldBe EvaluationReason.Fallthrough
        FlagEvaluationEngine.evaluate(fFull, c).reason shouldBe EvaluationReason.PercentageRollout
      }
    }
  }
}
