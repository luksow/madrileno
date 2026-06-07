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
    variantType: VariantType
  ): FeatureFlag = FeatureFlag(
    id = FlagId(UUID.randomUUID()),
    key = FlagKey("test-flag"),
    description = "",
    variantType = variantType,
    enabled = enabled,
    defaultValue = defaultValue,
    clientExposed = false,
    createdAt = now,
    updatedAt = now
  )

  private val ctx = EvaluationContext.anonymous(TargetingKey("user-1"))

  "FlagEvaluationEngine.evaluate" should {
    "return defaultValue with Fallthrough reason when the flag is enabled" in {
      val f      = flag(enabled = true, FlagVariant.BoolVariant(true), VariantType.Boolean)
      val result = FlagEvaluationEngine.evaluate(f, ctx)
      result.value shouldBe FlagVariant.BoolVariant(true)
      result.reason shouldBe EvaluationReason.Fallthrough
    }

    "return defaultValue with FlagDisabled reason when the flag is disabled" in {
      val f      = flag(enabled = false, FlagVariant.StringVariant("off"), VariantType.String)
      val result = FlagEvaluationEngine.evaluate(f, ctx)
      result.value shouldBe FlagVariant.StringVariant("off")
      result.reason shouldBe EvaluationReason.FlagDisabled
    }

    "preserve the variant for all four types" in {
      val cases: List[(VariantType, FlagVariant)] = List(
        (VariantType.Boolean, FlagVariant.BoolVariant(true)),
        (VariantType.String, FlagVariant.StringVariant("hello")),
        (VariantType.Int, FlagVariant.IntVariant(42)),
        (VariantType.Json, FlagVariant.JsonVariant(io.circe.Json.obj("k" -> io.circe.Json.fromString("v"))))
      )
      cases.foreach { case (vt, value) =>
        val f      = flag(enabled = true, value, vt)
        val result = FlagEvaluationEngine.evaluate(f, ctx)
        result.value shouldBe value
      }
    }
  }
}
