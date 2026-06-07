package madrileno.utils.featureflag

import cats.effect.IO
import cats.effect.testing.scalatest.AsyncIOSpec
import io.circe.Json
import madrileno.support.TestFeatureFlagService
import madrileno.utils.featureflag.domain.*
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec

class TestFeatureFlagServiceSpec extends AsyncWordSpec with AsyncIOSpec with Matchers {

  private val ctx = EvaluationContext.anonymous(TargetingKey("user-1"))

  "TestFeatureFlagService" should {
    "return the stubbed value when the flag is present and the variant matches" in {
      val svc = TestFeatureFlagService(
        FlagKey("flag-bool")   -> FlagVariant.BoolVariant(true),
        FlagKey("flag-string") -> FlagVariant.StringVariant("v3"),
        FlagKey("flag-int")    -> FlagVariant.IntVariant(42),
        FlagKey("flag-json")   -> FlagVariant.JsonVariant(Json.obj("k" -> Json.fromString("v")))
      )
      for {
        b <- svc.evaluator(ctx).booleanDetail(FlagKey("flag-bool"), default = false)
        s <- svc.evaluator(ctx).stringDetail(FlagKey("flag-string"), default = "x")
        i <- svc.evaluator(ctx).intDetail(FlagKey("flag-int"), default = 0)
        j <- svc.evaluator(ctx).jsonDetail(FlagKey("flag-json"), default = Json.Null)
      } yield {
        b.value shouldBe true
        s.value shouldBe "v3"
        i.value shouldBe 42
        j.value shouldBe Json.obj("k" -> Json.fromString("v"))
      }
    }

    "return the caller default with FlagNotFound when the flag is absent" in {
      TestFeatureFlagService.empty
        .evaluator(ctx)
        .booleanDetail(FlagKey("missing"), default = false)
        .asserting { d =>
          d.value shouldBe false
          d.reason shouldBe EvaluationReason.FlagNotFound
        }
    }

    "return VariantTypeMismatch when the stub variant doesn't match the caller's type" in {
      TestFeatureFlagService(FlagKey("flag-string") -> FlagVariant.StringVariant("v3"))
        .evaluator(ctx)
        .booleanDetail(FlagKey("flag-string"), default = false)
        .asserting { d =>
          d.value shouldBe false
          d.reason shouldBe EvaluationReason.VariantTypeMismatch
        }
    }
  }
}
