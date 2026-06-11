package madrileno.utils.featureflag.services

import cats.effect.IO
import cats.effect.std.Supervisor
import io.circe.Json
import madrileno.utils.async.Memoize
import madrileno.utils.cache.{Cache, CacheRuntime}
import madrileno.utils.db.transactor.Transactor
import madrileno.utils.events.EventBus
import madrileno.utils.featureflag.domain.*
import madrileno.utils.featureflag.repositories.FeatureFlagRepository
import madrileno.utils.observability.{LoggingSupport, TelemetryContext}

import scala.concurrent.duration.*

trait FeatureFlagService {
  def evaluator(ctx: EvaluationContext): FlagEvaluator
  def saveFlag(flag: FeatureFlag): IO[Unit]

  final def evaluateBoolean(key: FlagKey, default: Boolean)(using ctx: EvaluationContext): IO[Boolean] = evaluator(ctx).boolean(key, default)
  final def evaluateString(key: FlagKey, default: String)(using ctx: EvaluationContext): IO[String]    = evaluator(ctx).string(key, default)
  final def evaluateInt(key: FlagKey, default: Int)(using ctx: EvaluationContext): IO[Int]             = evaluator(ctx).int(key, default)
  final def evaluateJson(key: FlagKey, default: Json)(using ctx: EvaluationContext): IO[Json]          = evaluator(ctx).json(key, default)

  final def evaluateBooleanDetail(key: FlagKey, default: Boolean)(using ctx: EvaluationContext): IO[EvaluationDetail[Boolean]] =
    evaluator(ctx).booleanDetail(key, default)
  final def evaluateStringDetail(key: FlagKey, default: String)(using ctx: EvaluationContext): IO[EvaluationDetail[String]] =
    evaluator(ctx).stringDetail(key, default)
  final def evaluateIntDetail(key: FlagKey, default: Int)(using ctx: EvaluationContext): IO[EvaluationDetail[Int]] =
    evaluator(ctx).intDetail(key, default)
  final def evaluateJsonDetail(key: FlagKey, default: Json)(using ctx: EvaluationContext): IO[EvaluationDetail[Json]] =
    evaluator(ctx).jsonDetail(key, default)
}

trait FlagEvaluator {
  def booleanDetail(key: FlagKey, default: Boolean): IO[EvaluationDetail[Boolean]]
  def stringDetail(key: FlagKey, default: String): IO[EvaluationDetail[String]]
  def intDetail(key: FlagKey, default: Int): IO[EvaluationDetail[Int]]
  def jsonDetail(key: FlagKey, default: Json): IO[EvaluationDetail[Json]]

  final def boolean(key: FlagKey, default: Boolean): IO[Boolean] = booleanDetail(key, default).map(_.value)
  final def string(key: FlagKey, default: String): IO[String]    = stringDetail(key, default).map(_.value)
  final def int(key: FlagKey, default: Int): IO[Int]             = intDetail(key, default).map(_.value)
  final def json(key: FlagKey, default: Json): IO[Json]          = jsonDetail(key, default).map(_.value)
}

object FlagEvaluator {
  val asBoolean: FlagVariant => Option[Boolean] = { case FlagVariant.BoolVariant(v) => Some(v); case _ => None }
  val asString: FlagVariant => Option[String]   = { case FlagVariant.StringVariant(v) => Some(v); case _ => None }
  val asInt: FlagVariant => Option[Int]         = { case FlagVariant.IntVariant(v) => Some(v); case _ => None }
  val asJson: FlagVariant => Option[Json]       = { case FlagVariant.JsonVariant(v) => Some(v); case _ => None }
}

class FeatureFlagServiceLive(
  repository: FeatureFlagRepository,
  cacheRuntime: CacheRuntime,
  transactor: Transactor,
  eventBus: EventBus[FeatureFlagEvent]
)(using
  TelemetryContext,
  Supervisor[IO])
    extends FeatureFlagService
    with LoggingSupport {

  private val flagCache: Cache[FlagKey, Option[FeatureFlag]] =
    cacheRuntime.expiring[FlagKey, Option[FeatureFlag]](expireAfterWrite = 60.seconds, maxSize = 10_000)

  private val invalidationLoop: IO[Unit] =
    eventBus.subscribe
      .evalTap { case FeatureFlagEvent.Invalidated(key) => flagCache.invalidate(key) }
      .compile
      .drain

  private val ensureSubscribedToInvalidations: IO[Unit] =
    Memoize(summon[Supervisor[IO]].supervise(invalidationLoop).void)

  private def fetch(key: FlagKey): IO[Option[FeatureFlag]] =
    ensureSubscribedToInvalidations *> flagCache.get(key).flatMap {
      case Some(cached) => IO.pure(cached)
      case None         => transactor.inSession(repository.findByKey(key)).flatTap(flagCache.put(key, _))
    }

  override def saveFlag(flag: FeatureFlag): IO[Unit] =
    transactor.inSession(repository.save(flag)) *> eventBus.publish(FeatureFlagEvent.Invalidated(flag.key))

  private def evalDetail[T](
    key: FlagKey,
    default: T,
    extract: FlagVariant => Option[T],
    ctx: EvaluationContext
  ): IO[EvaluationDetail[T]] =
    fetch(key)
      .map {
        case None => EvaluationDetail(default, EvaluationReason.FlagNotFound)
        case Some(flag) =>
          val result = FlagEvaluationEngine.evaluate(flag, ctx)
          extract(result.value) match {
            case Some(v) => EvaluationDetail(v, result.reason)
            case None =>
              val actual = result.value.variantType
              EvaluationDetail(
                default,
                EvaluationReason.VariantTypeMismatch,
                Some(s"flag ${flag.key} returned $actual; caller expected a different type")
              )
          }
      }
      .handleErrorWith(t =>
        logger
          .warn(t)(s"feature-flag eval failed for $key, using default")
          .as(EvaluationDetail(default, EvaluationReason.Error, Option(t.getClass.getSimpleName)))
      )

  override def evaluator(ctx: EvaluationContext): FlagEvaluator = new FlagEvaluator {
    override def booleanDetail(key: FlagKey, default: Boolean): IO[EvaluationDetail[Boolean]] = evalDetail(key, default, FlagEvaluator.asBoolean, ctx)
    override def stringDetail(key: FlagKey, default: String): IO[EvaluationDetail[String]]    = evalDetail(key, default, FlagEvaluator.asString, ctx)
    override def intDetail(key: FlagKey, default: Int): IO[EvaluationDetail[Int]]             = evalDetail(key, default, FlagEvaluator.asInt, ctx)
    override def jsonDetail(key: FlagKey, default: Json): IO[EvaluationDetail[Json]]          = evalDetail(key, default, FlagEvaluator.asJson, ctx)
  }
}
