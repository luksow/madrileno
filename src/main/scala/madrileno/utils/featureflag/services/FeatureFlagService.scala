package madrileno.utils.featureflag.services

import cats.effect.std.Supervisor
import cats.effect.{Clock, IO, Ref}
import cats.syntax.all.*
import io.circe.Json
import madrileno.utils.async.Memoize
import madrileno.utils.cache.{Cache, CacheRuntime}
import madrileno.utils.db.transactor.Transactor
import madrileno.utils.events.EventBus
import madrileno.utils.featureflag.domain.*
import madrileno.utils.featureflag.repositories.{FeatureFlagRepository, SegmentRepository}
import madrileno.utils.observability.{LoggingSupport, TelemetryContext}

import scala.concurrent.duration.*

trait FeatureFlagService {
  def evaluator(ctx: EvaluationContext): FlagEvaluator

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
  segmentRepository: SegmentRepository,
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

  private val segmentCache: Cache[Unit, Map[SegmentName, Segment]] =
    cacheRuntime.expiring[Unit, Map[SegmentName, Segment]](expireAfterWrite = 60.seconds, maxSize = 1)

  def saveFlag(flag: FeatureFlag): IO[Unit] = {
    val expected   = flag.defaultValue.variantType
    val mismatched = flag.rules.filter(_.outcome.variant.variantType != expected)
    IO.raiseError(
      new IllegalArgumentException(s"flag ${flag.key} is $expected but rules at ${mismatched.map(_.position)} carry a different variant type")
    ).whenA(mismatched.nonEmpty) *>
      Clock[IO].realTimeInstant.flatMap(now => transactor.inTransaction(repository.save(flag.copy(updatedAt = now)))) *>
      invalidateFlag(flag.key) *>
      publishBestEffort(FeatureFlagEvent.Invalidated(flag.key))
  }

  def saveSegment(segment: Segment): IO[Unit] =
    Clock[IO].realTimeInstant.flatMap(now => transactor.inSession(segmentRepository.save(segment.copy(updatedAt = now)))) *>
      invalidateSegments *>
      publishBestEffort(FeatureFlagEvent.SegmentsChanged)

  private val invalidationEpoch: Ref[IO, Long] = Ref.unsafe(0L)

  private def invalidateFlag(key: FlagKey): IO[Unit] =
    invalidationEpoch.update(_ + 1) *> flagCache.invalidate(key)

  private val invalidateSegments: IO[Unit] =
    invalidationEpoch.update(_ + 1) *> segmentCache.invalidate(())

  private def publishBestEffort(event: FeatureFlagEvent): IO[Unit] =
    eventBus.publish(event).handleErrorWith(t => logger.warn(t)(s"feature-flag event publish failed, peers stay stale until TTL: $event"))

  private val invalidationLoop: IO[Nothing] = {
    eventBus.subscribeAwait
      .use {
        _.evalTap {
          case FeatureFlagEvent.Invalidated(key) => invalidateFlag(key)
          case FeatureFlagEvent.SegmentsChanged  => invalidateSegments
        }.compile.drain
      }
      .handleErrorWith(t => logger.warn(t)("feature-flag invalidation stream failed, resubscribing")) *>
      IO.sleep(1.second)
  }.foreverM

  private val invalidationStarted: IO[Unit] =
    Memoize(summon[Supervisor[IO]].supervise(invalidationLoop).void)

  // put-then-verify: the loop bumps the epoch before invalidating, so a load that raced an
  // invalidation either sees the moved epoch (and removes its own entry) or its entry is
  // removed by the invalidation that follows the bump — every interleaving converges.
  private def cachedLoad[K, V](
    cache: Cache[K, V],
    key: K,
    load: IO[V]
  ): IO[V] =
    cache.get(key).flatMap {
      case Some(cached) => IO.pure(cached)
      case None =>
        for {
          before <- invalidationEpoch.get
          loaded <- load
          _      <- cache.put(key, loaded)
          after  <- invalidationEpoch.get
          _      <- cache.invalidate(key).whenA(before != after)
        } yield loaded
    }

  private def fetchFlag(key: FlagKey): IO[Option[FeatureFlag]] =
    cachedLoad(flagCache, key, transactor.inSession(repository.findByKey(key)))

  private def fetchSegments(flag: FeatureFlag): IO[Map[SegmentName, Segment]] = {
    val referencesSegments = flag.rules.exists(_.conditions.exists {
      case RuleCondition.SegmentMatch(_) => true
      case _                             => false
    })
    if (!referencesSegments) IO.pure(Map.empty)
    else cachedLoad(segmentCache, (), transactor.inSession(segmentRepository.findAll).map(_.map(s => s.name -> s).toMap))
  }

  private def evalDetail[T](
    key: FlagKey,
    default: T,
    extract: FlagVariant => Option[T],
    ctx: EvaluationContext
  ): IO[EvaluationDetail[T]] =
    (invalidationStarted *> fetchFlag(key))
      .flatMap {
        case None => IO.pure(EvaluationDetail(default, EvaluationReason.FlagNotFound))
        case Some(flag) =>
          fetchSegments(flag).map { segments =>
            val result = FlagEvaluationEngine.evaluate(flag, segments, ctx)
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
