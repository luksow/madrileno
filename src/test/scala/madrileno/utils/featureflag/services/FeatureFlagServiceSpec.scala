package madrileno.utils.featureflag.services

import cats.effect.IO
import cats.effect.std.UUIDGen
import cats.effect.testing.scalatest.AsyncIOSpec
import io.opentelemetry.api.OpenTelemetry
import madrileno.support.{TestCacheRuntime, TestTransactor}
import madrileno.utils.featureflag.domain.*
import madrileno.utils.featureflag.repositories.FeatureFlagRepository
import madrileno.utils.observability.TelemetryContext
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import org.typelevel.otel4s.metrics.Meter
import org.typelevel.otel4s.trace.Tracer

import java.time.Instant

class FeatureFlagServiceSpec extends AsyncWordSpec with AsyncIOSpec with Matchers with TestTransactor {

  given TelemetryContext = TelemetryContext(Meter.noop[IO], Tracer.noop[IO], OpenTelemetry.noop())

  private val repository = new FeatureFlagRepository
  private def service    = new FeatureFlagServiceLive(repository, TestCacheRuntime.unbounded, transactor)

  private val now = Instant.parse("2026-06-07T00:00:00Z")
  private val ctx = EvaluationContext.anonymous(TargetingKey("user-1"))

  private def seedFlag(
    key: String,
    enabled: Boolean,
    defaultValue: FlagVariant,
    variantType: VariantType
  ): IO[FeatureFlag] =
    for {
      id <- UUIDGen[IO].randomUUID.map(FlagId.apply)
      flag = FeatureFlag(
               id = id,
               key = FlagKey(key),
               description = "",
               variantType = variantType,
               enabled = enabled,
               defaultValue = defaultValue,
               clientExposed = false,
               createdAt = now,
               updatedAt = now
             )
      _ <- transactor.inSession(repository.save(flag))
    } yield flag

  "FeatureFlagServiceLive" should {
    "return defaultValue for an enabled boolean flag" in {
      for {
        _      <- seedFlag("phase1-bool-on", enabled = true, FlagVariant.BoolVariant(true), VariantType.Boolean)
        result <- service.evaluator(ctx).booleanDetail(FlagKey("phase1-bool-on"), default = false)
      } yield {
        result.value shouldBe true
        result.reason shouldBe EvaluationReason.Fallthrough
      }
    }

    "return defaultValue with FlagDisabled when the flag is disabled" in {
      for {
        _      <- seedFlag("phase1-bool-off", enabled = false, FlagVariant.BoolVariant(true), VariantType.Boolean)
        result <- service.evaluator(ctx).booleanDetail(FlagKey("phase1-bool-off"), default = false)
      } yield {
        result.value shouldBe true // defaultValue from the flag, not from the call site
        result.reason shouldBe EvaluationReason.FlagDisabled
      }
    }

    "return the caller default with FlagNotFound when the flag does not exist" in {
      service
        .evaluator(ctx)
        .booleanDetail(FlagKey("phase1-missing"), default = false)
        .asserting { result =>
          result.value shouldBe false
          result.reason shouldBe EvaluationReason.FlagNotFound
        }
    }

    "return VariantTypeMismatch when the flag's variant doesn't match the caller's typed call" in {
      for {
        _      <- seedFlag("phase1-string-flag", enabled = true, FlagVariant.StringVariant("v3"), VariantType.String)
        result <- service.evaluator(ctx).booleanDetail(FlagKey("phase1-string-flag"), default = false)
      } yield {
        result.value shouldBe false
        result.reason shouldBe EvaluationReason.VariantTypeMismatch
      }
    }

    "support int and json variant types" in {
      for {
        _ <- seedFlag("phase1-int", enabled = true, FlagVariant.IntVariant(42), VariantType.Int)
        _ <-
          seedFlag("phase1-json", enabled = true, FlagVariant.JsonVariant(io.circe.Json.obj("k" -> io.circe.Json.fromString("v"))), VariantType.Json)
        i <- service.evaluator(ctx).intDetail(FlagKey("phase1-int"), default = 0)
        j <- service.evaluator(ctx).jsonDetail(FlagKey("phase1-json"), default = io.circe.Json.Null)
      } yield {
        i.value shouldBe 42
        j.value shouldBe io.circe.Json.obj("k" -> io.circe.Json.fromString("v"))
      }
    }

  }
}
