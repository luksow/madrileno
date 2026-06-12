package madrileno.utils.featureflag.services

import cats.effect.std.Supervisor
import cats.effect.{Clock, IO, Ref}
import cats.syntax.all.*
import io.circe.Json
import madrileno.utils.async.Memoize
import madrileno.utils.cache.{Cache, CacheRuntime}
import madrileno.utils.crypto.IdGenerator
import madrileno.utils.db.transactor.Transactor
import madrileno.utils.events.EventBus
import madrileno.utils.featureflag.domain.*
import madrileno.utils.featureflag.repositories.{FeatureFlagAuditRepository, FeatureFlagRepository, RuleRepository, SegmentRepository}
import madrileno.utils.observability.{LoggingSupport, TelemetryContext}
import madrileno.utils.pagination.PageRequest

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
  ruleRepository: RuleRepository,
  segmentRepository: SegmentRepository,
  auditRepository: FeatureFlagAuditRepository,
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

  private val clientFlagsCache: Cache[Unit, List[FeatureFlag]] =
    cacheRuntime.expiring[Unit, List[FeatureFlag]](expireAfterWrite = 60.seconds, maxSize = 1)

  def createFlag(flag: FeatureFlag, actor: Actor): IO[CreateFlagResult] =
    validate(flag) match {
      case Left(message) => IO.pure(CreateFlagResult.Invalid(message))
      case Right(()) =>
        transactor.inSession(repository.findByKey(flag.key)).flatMap {
          case Some(_) => IO.pure(CreateFlagResult.KeyExists)
          case None    => persistFlag(flag, AuditAction.Created, before = None, actor).map(CreateFlagResult.Created.apply)
        }
    }

  def updateFlag(
    key: FlagKey,
    transform: FeatureFlag => FeatureFlag,
    actor: Actor
  ): IO[UpdateFlagResult] = modifyFlag(key, transform, AuditAction.Updated, actor)

  def toggleFlag(
    key: FlagKey,
    enabled: Boolean,
    actor: Actor
  ): IO[UpdateFlagResult] = modifyFlag(key, _.copy(enabled = enabled), AuditAction.Toggled, actor)

  private def modifyFlag(
    key: FlagKey,
    transform: FeatureFlag => FeatureFlag,
    action: AuditAction,
    actor: Actor
  ): IO[UpdateFlagResult] =
    transactor.inSession(repository.findByKey(key)).flatMap {
      case None => IO.pure(UpdateFlagResult.NotFound)
      case Some(existing) =>
        val next = transform(existing).copy(id = existing.id, key = existing.key, createdAt = existing.createdAt)
        validate(next) match {
          case Left(message) => IO.pure(UpdateFlagResult.Invalid(message))
          case Right(())     => persistFlag(next, action, before = Some(existing), actor).map(UpdateFlagResult.Updated.apply)
        }
    }

  def deleteFlag(key: FlagKey, actor: Actor): IO[DeleteFlagResult] =
    transactor.inSession(repository.findByKey(key)).flatMap {
      case None => IO.pure(DeleteFlagResult.NotFound)
      case Some(flag) =>
        for {
          now     <- Clock[IO].realTimeInstant
          entryId <- IdGenerator.generateId(AuditEntryId)
          _ <- transactor.inTransaction {
                 auditRepository
                   .append(AuditEntry(entryId, flagId = None, flag.key, actor, AuditAction.Deleted, Some(flag), after = None, now)) *>
                   auditRepository.detachFlag(flag.id) *>
                   ruleRepository.deleteByFlagId(flag.id) *>
                   repository.deleteById(flag.id)
               }
          _ <- invalidateFlag(flag.key)
          _ <- publishBestEffort(FeatureFlagEvent.Invalidated(flag.key))
        } yield DeleteFlagResult.Deleted
    }

  def listFlags: IO[List[FeatureFlag]] = transactor.inSession(repository.findAll)

  def getFlag(key: FlagKey): IO[Option[FeatureFlag]] = transactor.inSession(repository.findByKey(key))

  def listAudit(key: FlagKey, page: PageRequest[AuditSortField]): IO[(List[AuditEntry], Long)] =
    transactor.inSession(auditRepository.listByFlagKey(key, page))

  def listSegments: IO[List[Segment]] = transactor.inSession(segmentRepository.findAll)

  def createSegment(segment: Segment): IO[CreateSegmentResult] =
    transactor.inSession(segmentRepository.findByName(segment.name)).flatMap {
      case Some(_) => IO.pure(CreateSegmentResult.NameExists)
      case None    => persistSegment(segment).map(CreateSegmentResult.Created.apply)
    }

  def updateSegment(name: SegmentName, transform: Segment => Segment): IO[UpdateSegmentResult] =
    transactor.inSession(segmentRepository.findByName(name)).flatMap {
      case None => IO.pure(UpdateSegmentResult.NotFound)
      case Some(existing) =>
        val next = transform(existing).copy(id = existing.id, name = existing.name, createdAt = existing.createdAt)
        persistSegment(next).map(UpdateSegmentResult.Updated.apply)
    }

  def deleteSegment(name: SegmentName): IO[DeleteSegmentResult] =
    transactor.inSession(segmentRepository.findByName(name)).flatMap {
      case None => IO.pure(DeleteSegmentResult.NotFound)
      case Some(segment) =>
        transactor.inSession(segmentRepository.deleteById(segment.id)) *>
          invalidateSegments *>
          publishBestEffort(FeatureFlagEvent.SegmentsChanged).as(DeleteSegmentResult.Deleted)
    }

  def evaluateForDebug(key: FlagKey, ctx: EvaluationContext): IO[Option[FlagEvaluationEngine.Result]] =
    transactor.inSession {
      repository.findByKey(key).flatMap {
        case None => IO.pure(None)
        case Some(flag) =>
          segmentRepository.findAll.map(segments => Some(FlagEvaluationEngine.evaluate(flag, segments.map(s => s.name -> s).toMap, ctx)))
      }
    }

  def evaluateClientExposed(ctx: EvaluationContext): IO[Map[FlagKey, Json]] =
    (invalidationStarted *> cachedLoad(clientFlagsCache, (), transactor.inSession(repository.findAllClientExposed)))
      .flatMap(_.traverse(flag => fetchSegments(flag).map(segments => flag.key -> FlagEvaluationEngine.evaluate(flag, segments, ctx).value.toJson)))
      .map(_.toMap)

  private def persistFlag(
    flag: FeatureFlag,
    action: AuditAction,
    before: Option[FeatureFlag],
    actor: Actor
  ): IO[FeatureFlag] =
    for {
      now <- Clock[IO].realTimeInstant
      stamped = flag.updated(now)
      entryId <- IdGenerator.generateId(AuditEntryId)
      _ <- transactor.inTransaction {
             repository.save(stamped) *>
               auditRepository.append(AuditEntry(entryId, Some(stamped.id), stamped.key, actor, action, before, Some(stamped), now))
           }
      _ <- invalidateFlag(stamped.key)
      _ <- publishBestEffort(FeatureFlagEvent.Invalidated(stamped.key))
    } yield stamped

  private def persistSegment(segment: Segment): IO[Segment] =
    for {
      now <- Clock[IO].realTimeInstant
      stamped = segment.updated(now)
      _ <- transactor.inSession(segmentRepository.save(stamped))
      _ <- invalidateSegments
      _ <- publishBestEffort(FeatureFlagEvent.SegmentsChanged)
    } yield stamped

  private def validate(flag: FeatureFlag): Either[String, Unit] = {
    val expected   = flag.defaultValue.variantType
    val mismatched = flag.rules.filter(_.outcome.variant.variantType != expected)
    val duplicated = flag.rules.groupBy(_.position).collect { case (position, rules) if rules.sizeIs > 1 => position }
    if (mismatched.nonEmpty) Left(s"flag ${flag.key} is $expected but rules at ${mismatched.map(_.position)} carry a different variant type")
    else if (duplicated.nonEmpty) Left(s"flag ${flag.key} has duplicate rule positions: ${duplicated.mkString(", ")}")
    else Right(())
  }

  private val invalidationEpoch: Ref[IO, Long] = Ref.unsafe(0L)

  private def invalidateFlag(key: FlagKey): IO[Unit] =
    invalidationEpoch.update(_ + 1) *> flagCache.invalidate(key) *> clientFlagsCache.invalidate(())

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
        case None => IO.pure(EvaluationDetail(default, EvaluationReason.Error, Some(EvaluationErrorCode.FlagNotFound)))
        case Some(flag) =>
          fetchSegments(flag).map { segments =>
            val result = FlagEvaluationEngine.evaluate(flag, segments, ctx)
            extract(result.value) match {
              case Some(v) => EvaluationDetail(v, result.reason)
              case None =>
                val actual = result.value.variantType
                EvaluationDetail(
                  default,
                  EvaluationReason.Error,
                  Some(EvaluationErrorCode.TypeMismatch),
                  Some(s"flag ${flag.key} returned $actual; caller expected a different type")
                )
            }
          }
      }
      .handleErrorWith(t =>
        logger
          .warn(t)(s"feature-flag eval failed for $key, using default")
          .as(EvaluationDetail(default, EvaluationReason.Error, Some(EvaluationErrorCode.General), Option(t.getClass.getSimpleName)))
      )

  override def evaluator(ctx: EvaluationContext): FlagEvaluator = new FlagEvaluator {
    override def booleanDetail(key: FlagKey, default: Boolean): IO[EvaluationDetail[Boolean]] = evalDetail(key, default, FlagEvaluator.asBoolean, ctx)
    override def stringDetail(key: FlagKey, default: String): IO[EvaluationDetail[String]]    = evalDetail(key, default, FlagEvaluator.asString, ctx)
    override def intDetail(key: FlagKey, default: Int): IO[EvaluationDetail[Int]]             = evalDetail(key, default, FlagEvaluator.asInt, ctx)
    override def jsonDetail(key: FlagKey, default: Json): IO[EvaluationDetail[Json]]          = evalDetail(key, default, FlagEvaluator.asJson, ctx)
  }
}

enum CreateFlagResult {
  case Created(flag: FeatureFlag) extends CreateFlagResult
  case KeyExists                  extends CreateFlagResult
  case Invalid(message: String)   extends CreateFlagResult
}

enum UpdateFlagResult {
  case Updated(flag: FeatureFlag) extends UpdateFlagResult
  case NotFound                   extends UpdateFlagResult
  case Invalid(message: String)   extends UpdateFlagResult
}

enum DeleteFlagResult {
  case Deleted, NotFound
}

enum CreateSegmentResult {
  case Created(segment: Segment) extends CreateSegmentResult
  case NameExists                extends CreateSegmentResult
}

enum UpdateSegmentResult {
  case Updated(segment: Segment) extends UpdateSegmentResult
  case NotFound                  extends UpdateSegmentResult
}

enum DeleteSegmentResult {
  case Deleted, NotFound
}
