package madrileno.utils.featureflag.services

import cats.effect.IO
import cats.effect.std.Supervisor
import cats.effect.testing.scalatest.AsyncIOSpec
import io.opentelemetry.api.OpenTelemetry
import madrileno.support.{TestCacheRuntime, TestData, TestTransactor}
import madrileno.utils.crypto.IdGenerator
import madrileno.utils.events.{EventBus, EventBusRuntime}
import madrileno.utils.featureflag.domain.*
import madrileno.utils.featureflag.repositories.{FeatureFlagRepository, RuleRepository, SegmentRepository}
import madrileno.utils.observability.TelemetryContext
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import org.typelevel.otel4s.metrics.Meter
import org.typelevel.otel4s.trace.Tracer

import scala.concurrent.duration.DurationInt

class FeatureFlagServiceSpec extends AsyncWordSpec with AsyncIOSpec with Matchers with TestTransactor {

  given TelemetryContext = TelemetryContext(Meter.noop[IO], Tracer.noop[IO], OpenTelemetry.noop())

  private val (supervisorInstance, supervisorRelease) = Supervisor[IO].allocated.unsafeRunSync()
  given Supervisor[IO]                                = supervisorInstance

  override def beforeContainersStop(containers: Containers): Unit = {
    supervisorRelease.unsafeRunSync()
    super.beforeContainersStop(containers)
  }

  private val eventBus: EventBus[FeatureFlagEvent] =
    EventBusRuntime.local.topic[FeatureFlagEvent]("feature_flag_events_test", maxQueued = 64)

  private lazy val service =
    new FeatureFlagServiceLive(new FeatureFlagRepository(new RuleRepository), new SegmentRepository, TestCacheRuntime.unbounded, transactor, eventBus)

  private val ctx = EvaluationContext.anonymous(TargetingKey("user-1"))

  private def seedFlag(
    key: String,
    enabled: Boolean = true,
    defaultValue: FlagVariant = FlagVariant.BoolVariant(true),
    rules: List[Rule] = Nil
  ): IO[FeatureFlag] =
    for {
      id <- IdGenerator.generateId(FlagId)
      flag = TestData.featureFlag(id = id, key = FlagKey(key), enabled = enabled, defaultValue = defaultValue, rules = rules)
      _ <- service.saveFlag(flag)
    } yield flag

  "FeatureFlagServiceLive" should {
    "return defaultValue for an enabled boolean flag" in {
      for {
        _      <- seedFlag("phase1-bool-on")
        result <- service.evaluator(ctx).booleanDetail(FlagKey("phase1-bool-on"), default = false)
      } yield {
        result.value shouldBe true
        result.reason shouldBe EvaluationReason.Default
      }
    }

    "return defaultValue with Disabled when the flag is disabled" in {
      for {
        _      <- seedFlag("phase1-bool-off", enabled = false)
        result <- service.evaluator(ctx).booleanDetail(FlagKey("phase1-bool-off"), default = false)
      } yield {
        result.value shouldBe true
        result.reason shouldBe EvaluationReason.Disabled
      }
    }

    "return the caller default with ErrorCode.FlagNotFound when the flag does not exist" in {
      service
        .evaluator(ctx)
        .booleanDetail(FlagKey("phase1-missing"), default = false)
        .asserting { result =>
          result.value shouldBe false
          result.reason shouldBe EvaluationReason.Error
          result.errorCode shouldBe Some(ErrorCode.FlagNotFound)
        }
    }

    "return ErrorCode.TypeMismatch when the flag's variant doesn't match the caller's typed call" in {
      for {
        _      <- seedFlag("phase1-string-flag", defaultValue = FlagVariant.StringVariant("v3"))
        result <- service.evaluator(ctx).booleanDetail(FlagKey("phase1-string-flag"), default = false)
      } yield {
        result.value shouldBe false
        result.reason shouldBe EvaluationReason.Error
        result.errorCode shouldBe Some(ErrorCode.TypeMismatch)
      }
    }

    "return ErrorCode.TypeMismatch when calling evaluateJson on a non-Json flag" in {
      for {
        _      <- seedFlag("phase1-bool-as-json")
        result <- service.evaluator(ctx).jsonDetail(FlagKey("phase1-bool-as-json"), default = io.circe.Json.Null)
      } yield {
        result.value shouldBe io.circe.Json.Null
        result.reason shouldBe EvaluationReason.Error
        result.errorCode shouldBe Some(ErrorCode.TypeMismatch)
      }
    }

    "support int and json variant types" in {
      for {
        _ <- seedFlag("phase1-int", defaultValue = FlagVariant.IntVariant(42))
        _ <- seedFlag("phase1-json", defaultValue = FlagVariant.JsonVariant(io.circe.Json.obj("k" -> io.circe.Json.fromString("v"))))
        i <- service.evaluator(ctx).intDetail(FlagKey("phase1-int"), default = 0)
        j <- service.evaluator(ctx).jsonDetail(FlagKey("phase1-json"), default = io.circe.Json.Null)
      } yield {
        i.value shouldBe 42
        j.value shouldBe io.circe.Json.obj("k" -> io.circe.Json.fromString("v"))
      }
    }

    "persist rules through saveFlag and match them on evaluation" in {
      val enterpriseRule = TestData.flagRule(conditions = List(RuleCondition.StringEquals(AttributeName("plan"), "enterprise")))
      val ctxEnterprise  = EvaluationContext(TargetingKey("user-x"), Map(AttributeName("plan") -> AttributeValue("enterprise")))
      val ctxFree        = EvaluationContext(TargetingKey("user-y"), Map(AttributeName("plan") -> AttributeValue("free")))
      for {
        _   <- seedFlag("phase2-rule", defaultValue = FlagVariant.BoolVariant(false), rules = List(enterpriseRule))
        ent <- service.evaluator(ctxEnterprise).booleanDetail(FlagKey("phase2-rule"), default = false)
        fr  <- service.evaluator(ctxFree).booleanDetail(FlagKey("phase2-rule"), default = false)
      } yield {
        ent.value shouldBe true
        ent.reason shouldBe EvaluationReason.TargetingMatch
        fr.value shouldBe false
        fr.reason shouldBe EvaluationReason.Default
      }
    }

    "update an existing flag through saveFlag and see the change immediately on the writing instance" in {
      val key = FlagKey("phase2-toggle")
      for {
        flag     <- seedFlag(key.unwrap, enabled = true)
        first    <- service.evaluator(ctx).booleanDetail(key, default = false)
        _        <- service.saveFlag(flag.copy(enabled = false))
        afterInv <- service.evaluator(ctx).booleanDetail(key, default = false)
      } yield {
        first.reason shouldBe EvaluationReason.Default
        afterInv.reason shouldBe EvaluationReason.Disabled
      }
    }

    "replace rules on saveFlag instead of accumulating them" in {
      val key   = FlagKey("phase2-rule-replace")
      val ruleA = TestData.flagRule(outcome = RuleOutcome.FixedValue(FlagVariant.StringVariant("a")))
      val ruleB = TestData.flagRule(outcome = RuleOutcome.FixedValue(FlagVariant.StringVariant("b")))
      for {
        flag   <- seedFlag(key.unwrap, defaultValue = FlagVariant.StringVariant("default"), rules = List(ruleA))
        _      <- service.saveFlag(flag.copy(rules = List(ruleB)))
        result <- service.evaluator(ctx).stringDetail(key, default = "caller-default")
      } yield result.value shouldBe "b"
    }

    "propagate invalidation to other service instances via the event bus" in {
      val key = FlagKey("phase2-cross-instance")
      val reader =
        new FeatureFlagServiceLive(
          new FeatureFlagRepository(new RuleRepository),
          new SegmentRepository,
          TestCacheRuntime.unbounded,
          transactor,
          eventBus
        )
      // republishing compensates for events published before the reader's background subscription was live
      def pollUntilDisabled(attempts: Int): IO[EvaluationDetail[Boolean]] =
        reader.evaluator(ctx).booleanDetail(key, default = false).flatMap { detail =>
          if (detail.reason == EvaluationReason.Disabled || attempts <= 0) IO.pure(detail)
          else eventBus.publish(FeatureFlagEvent.Invalidated(key)) *> IO.sleep(50.millis) *> pollUntilDisabled(attempts - 1)
        }
      for {
        flag       <- seedFlag(key.unwrap, enabled = true)
        first      <- reader.evaluator(ctx).booleanDetail(key, default = false)
        _          <- service.saveFlag(flag.copy(enabled = false))
        afterEvent <- pollUntilDisabled(attempts = 100)
      } yield {
        first.reason shouldBe EvaluationReason.Default
        afterEvent.reason shouldBe EvaluationReason.Disabled
      }
    }

    "reject saveFlag when rules share a position" in {
      val duplicated = TestData.featureFlag(
        key = FlagKey("phase2-dup-positions"),
        rules = List(TestData.flagRule(position = RulePosition(0)), TestData.flagRule(position = RulePosition(0)))
      )
      service.saveFlag(duplicated).attempt.asserting(_.left.toOption.get shouldBe an[IllegalArgumentException])
    }

    "reject saveFlag when a rule outcome's variant type doesn't match the flag's" in {
      val mismatched = TestData.featureFlag(
        key = FlagKey("phase2-mismatched"),
        defaultValue = FlagVariant.BoolVariant(false),
        rules = List(TestData.flagRule(outcome = RuleOutcome.FixedValue(FlagVariant.StringVariant("oops"))))
      )
      service.saveFlag(mismatched).attempt.asserting(_.left.toOption.get shouldBe an[IllegalArgumentException])
    }

    "evaluate segment-driven rules and pick up segment changes" in {
      val key         = FlagKey("phase2-segment")
      val segmentName = SegmentName("phase2-beta")
      val segmentV1   = TestData.flagSegment(name = segmentName, conditions = List(RuleCondition.StringEquals(AttributeName("plan"), "beta")))
      val rule        = TestData.flagRule(conditions = List(RuleCondition.SegmentMatch(segmentName)))
      val ctxBeta     = EvaluationContext(TargetingKey("user-b"), Map(AttributeName("plan") -> AttributeValue("beta")))
      for {
        _      <- service.saveSegment(segmentV1)
        _      <- seedFlag(key.unwrap, defaultValue = FlagVariant.BoolVariant(false), rules = List(rule))
        before <- service.evaluator(ctxBeta).booleanDetail(key, default = false)
        _      <- service.saveSegment(segmentV1.copy(conditions = List(RuleCondition.StringEquals(AttributeName("plan"), "enterprise"))))
        after  <- service.evaluator(ctxBeta).booleanDetail(key, default = false)
      } yield {
        before.reason shouldBe EvaluationReason.TargetingMatch
        after.reason shouldBe EvaluationReason.Default
      }
    }
  }
}
