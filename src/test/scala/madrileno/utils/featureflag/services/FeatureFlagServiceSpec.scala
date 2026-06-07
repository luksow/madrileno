package madrileno.utils.featureflag.services

import cats.effect.IO
import cats.effect.std.{Supervisor, UUIDGen}
import cats.effect.testing.scalatest.AsyncIOSpec
import cats.syntax.all.*
import io.opentelemetry.api.OpenTelemetry
import madrileno.support.{TestCacheRuntime, TestTransactor}
import madrileno.utils.events.{EventBus, EventBusRuntime}
import madrileno.utils.featureflag.domain.*
import madrileno.utils.featureflag.repositories.{FeatureFlagRepository, RuleRepository, SegmentRepository}
import madrileno.utils.observability.TelemetryContext
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import org.typelevel.otel4s.metrics.Meter
import org.typelevel.otel4s.trace.Tracer
import skunk.codec.all.*
import skunk.implicits.*

import java.time.Instant
import scala.concurrent.duration.DurationInt

class FeatureFlagServiceSpec extends AsyncWordSpec with AsyncIOSpec with Matchers with TestTransactor {

  given TelemetryContext = TelemetryContext(Meter.noop[IO], Tracer.noop[IO], OpenTelemetry.noop())
  given Supervisor[IO]   = Supervisor[IO].allocated.unsafeRunSync()._1
  private val eventBus: EventBus[FeatureFlagEvent] =
    EventBusRuntime.local.topic[FeatureFlagEvent]("feature_flag_events_test", maxQueued = 64)

  private val ruleRepo     = new RuleRepository
  private val segmentRepo  = new SegmentRepository
  private val repository   = new FeatureFlagRepository(ruleRepo, segmentRepo)
  private lazy val service = new FeatureFlagServiceLive(repository, TestCacheRuntime.unbounded, transactor, eventBus)

  private val now = Instant.parse("2026-06-07T00:00:00Z")
  private val ctx = EvaluationContext.anonymous(TargetingKey("user-1"))

  private def seedFlag(
    key: String,
    enabled: Boolean,
    defaultValue: FlagVariant,
    rules: List[Rule] = Nil
  ): IO[FeatureFlag] =
    for {
      id <- UUIDGen[IO].randomUUID.map(FlagId.apply)
      flag = FeatureFlag(
               id = id,
               key = FlagKey(key),
               description = FlagDescription(""),
               enabled = enabled,
               defaultValue = defaultValue,
               clientExposed = false,
               rules = rules,
               createdAt = now,
               updatedAt = now
             )
      _ <- transactor.inSession(repository.save(flag))
      _ <- rules.traverse_(rule => transactor.inSession(ruleRepo.save(id, rule)))
    } yield flag

  "FeatureFlagServiceLive" should {
    "return defaultValue for an enabled boolean flag" in {
      for {
        _      <- seedFlag("phase1-bool-on", enabled = true, FlagVariant.BoolVariant(true))
        result <- service.evaluator(ctx).booleanDetail(FlagKey("phase1-bool-on"), default = false)
      } yield {
        result.value shouldBe true
        result.reason shouldBe EvaluationReason.Fallthrough
      }
    }

    "return defaultValue with FlagDisabled when the flag is disabled" in {
      for {
        _      <- seedFlag("phase1-bool-off", enabled = false, FlagVariant.BoolVariant(true))
        result <- service.evaluator(ctx).booleanDetail(FlagKey("phase1-bool-off"), default = false)
      } yield {
        result.value shouldBe true
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
        _      <- seedFlag("phase1-string-flag", enabled = true, FlagVariant.StringVariant("v3"))
        result <- service.evaluator(ctx).booleanDetail(FlagKey("phase1-string-flag"), default = false)
      } yield {
        result.value shouldBe false
        result.reason shouldBe EvaluationReason.VariantTypeMismatch
      }
    }

    "return VariantTypeMismatch when calling evaluateJson on a non-Json flag" in {
      for {
        _      <- seedFlag("phase1-bool-as-json", enabled = true, FlagVariant.BoolVariant(true))
        result <- service.evaluator(ctx).jsonDetail(FlagKey("phase1-bool-as-json"), default = io.circe.Json.Null)
      } yield {
        result.value shouldBe io.circe.Json.Null
        result.reason shouldBe EvaluationReason.VariantTypeMismatch
      }
    }

    "support int and json variant types" in {
      for {
        _ <- seedFlag("phase1-int", enabled = true, FlagVariant.IntVariant(42))
        _ <- seedFlag("phase1-json", enabled = true, FlagVariant.JsonVariant(io.circe.Json.obj("k" -> io.circe.Json.fromString("v"))))
        i <- service.evaluator(ctx).intDetail(FlagKey("phase1-int"), default = 0)
        j <- service.evaluator(ctx).jsonDetail(FlagKey("phase1-json"), default = io.circe.Json.Null)
      } yield {
        i.value shouldBe 42
        j.value shouldBe io.circe.Json.obj("k" -> io.circe.Json.fromString("v"))
      }
    }

    "match a rule on string attribute and return its FixedValue" in {
      val enterpriseRule = Rule(
        id = RuleId(java.util.UUID.randomUUID()),
        position = RulePosition(0),
        description = FlagDescription(""),
        conditions = List(RuleCondition.StringEquals(AttributeName("plan"), "enterprise")),
        outcome = RuleOutcome.FixedValue(FlagVariant.BoolVariant(true)),
        createdAt = now
      )
      val ctxEnterprise = EvaluationContext(TargetingKey("user-x"), Map(AttributeName("plan") -> AttributeValue("enterprise")))
      val ctxFree       = EvaluationContext(TargetingKey("user-y"), Map(AttributeName("plan") -> AttributeValue("free")))
      for {
        _   <- seedFlag("phase2-rule", enabled = true, FlagVariant.BoolVariant(false), rules = List(enterpriseRule))
        ent <- service.evaluator(ctxEnterprise).booleanDetail(FlagKey("phase2-rule"), default = false)
        fr  <- service.evaluator(ctxFree).booleanDetail(FlagKey("phase2-rule"), default = false)
      } yield {
        ent.value shouldBe true
        ent.reason shouldBe EvaluationReason.RuleMatch
        fr.value shouldBe false
        fr.reason shouldBe EvaluationReason.Fallthrough
      }
    }

    "invalidate the cache when an Invalidated event is published" in {
      val key       = FlagKey("phase2-cache-inv")
      val warmedCtx = EvaluationContext.anonymous(TargetingKey("user-cache"))
      for {
        _ <- seedFlag(key.unwrap, enabled = true, FlagVariant.BoolVariant(true))
        // First lookup: warms the cache AND starts the subscription.
        first <- service.evaluator(warmedCtx).booleanDetail(key, default = false)
        // Mutate the DB directly behind the cache's back — flip enabled.
        _ <- transactor.inSession {
               val session = summon[skunk.Session[IO]]
               session.execute(sql"UPDATE feature_flag SET enabled = FALSE WHERE key = $text".command)(key.unwrap)
             }
        // Without invalidation, the cache still returns the original (enabled) state.
        stale <- service.evaluator(warmedCtx).booleanDetail(key, default = false)
        // Publish the invalidation event; subscription drops the cached entry.
        _        <- eventBus.publish(FeatureFlagEvent.Invalidated(key))
        _        <- IO.sleep(200.millis) // give the subscriber fiber time to consume + invalidate
        afterInv <- service.evaluator(warmedCtx).booleanDetail(key, default = false)
      } yield {
        first.reason shouldBe EvaluationReason.Fallthrough
        stale.reason shouldBe EvaluationReason.Fallthrough
        afterInv.reason shouldBe EvaluationReason.FlagDisabled
      }
    }
  }
}
