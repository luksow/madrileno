package madrileno.utils.featureflag.services

import cats.effect.std.Supervisor
import cats.effect.{Clock, IO, Ref}
import cats.syntax.all.*
import io.circe.Json
import madrileno.utils.async.Memoize
import madrileno.utils.cache.{Cache, CacheRuntime}
import madrileno.utils.crypto.IdGenerator
import madrileno.utils.db.dsl.Lock
import madrileno.utils.db.transactor.Transactor
import madrileno.utils.events.EventBus
import madrileno.utils.featureflag.domain.*
import madrileno.utils.featureflag.repositories.{FeatureFlagAuditRepository, FeatureFlagRepository, RuleRepository, SegmentRepository}
import madrileno.utils.observability.{LoggingSupport, TelemetryContext}
import madrileno.utils.pagination.PageRequest
import pl.iterators.sealedmonad.syntax.*

import java.time.Instant
import scala.concurrent.duration.*

trait FeatureFlagService {
  def evaluator(ctx: EvaluationContext): FlagEvaluator

  final def evaluateBoolean(
    key: FlagKey,
    ctx: EvaluationContext,
    default: Boolean
  ): IO[Boolean] = evaluator(ctx).boolean(key, default)
  final def evaluateString(
    key: FlagKey,
    ctx: EvaluationContext,
    default: String
  ): IO[String] = evaluator(ctx).string(key, default)
  final def evaluateInt(
    key: FlagKey,
    ctx: EvaluationContext,
    default: Int
  ): IO[Int] = evaluator(ctx).int(key, default)
  final def evaluateJson(
    key: FlagKey,
    ctx: EvaluationContext,
    default: Json
  ): IO[Json] = evaluator(ctx).json(key, default)

  final def evaluateBooleanDetail(
    key: FlagKey,
    ctx: EvaluationContext,
    default: Boolean
  ): IO[EvaluationDetail[Boolean]] =
    evaluator(ctx).booleanDetail(key, default)
  final def evaluateStringDetail(
    key: FlagKey,
    ctx: EvaluationContext,
    default: String
  ): IO[EvaluationDetail[String]] =
    evaluator(ctx).stringDetail(key, default)
  final def evaluateIntDetail(
    key: FlagKey,
    ctx: EvaluationContext,
    default: Int
  ): IO[EvaluationDetail[Int]] =
    evaluator(ctx).intDetail(key, default)
  final def evaluateJsonDetail(
    key: FlagKey,
    ctx: EvaluationContext,
    default: Json
  ): IO[EvaluationDetail[Json]] =
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

  def createFlag(command: CreateFlagCommand): IO[CreateFlagResult] =
    transactor
      .inTransaction {
        (for {
          flag <- buildFlag(command).seal[CreateFlagResult].attempt(f => validate(f).left.map(CreateFlagResult.Invalid.apply).map(_ => f))
          _ <- repository.insert(flag).seal[CreateFlagResult].attempt {
                 case true  => Right(())
                 case false => Left(CreateFlagResult.KeyExists)
               }
          entryId <- IdGenerator.generateId(AuditEntryId).seal
          entry = AuditEntry(entryId, Some(flag.id), flag.key, command.actor, AuditAction.Created, before = None, after = Some(flag), flag.createdAt)
          _ <- auditRepository.append(entry).seal
        } yield CreateFlagResult.Created(flag)).run
      }
      .flatTap {
        case CreateFlagResult.Created(flag) => invalidateAndPublishFlag(flag.key)
        case _                              => IO.unit
      }

  def updateFlag(command: UpdateFlagCommand): IO[UpdateFlagResult] =
    modifyFlag(command.key, command.actor, AuditAction.Updated) { (existing, now) =>
      buildRules(command.rules, now).map { rules =>
        existing.copy(
          description = command.description,
          enabled = command.enabled,
          defaultValue = command.defaultValue,
          clientExposed = command.clientExposed,
          rules = rules,
          updatedAt = now
        )
      }
    }

  def toggleFlag(command: ToggleFlagCommand): IO[UpdateFlagResult] =
    modifyFlag(command.key, command.actor, AuditAction.Toggled) { (existing, now) =>
      IO.pure(existing.copy(enabled = command.enabled, updatedAt = now))
    }

  private def modifyFlag(
    key: FlagKey,
    actor: Actor,
    action: AuditAction
  )(
    transform: (FeatureFlag, Instant) => IO[FeatureFlag]
  ): IO[UpdateFlagResult] =
    transactor
      .inTransaction {
        (for {
          now      <- Clock[IO].realTimeInstant.seal[UpdateFlagResult]
          existing <- repository.findByKey(key, Lock.ForUpdate).valueOr[UpdateFlagResult](UpdateFlagResult.NotFound)
          next     <- transform(existing, now).seal[UpdateFlagResult].attempt(n => validate(n).left.map(UpdateFlagResult.Invalid.apply).map(_ => n))
          entryId  <- IdGenerator.generateId(AuditEntryId).seal
          entry = AuditEntry(entryId, Some(next.id), next.key, actor, action, Some(existing), Some(next), now)
          _ <- (repository.update(next) *> auditRepository.append(entry)).seal
        } yield UpdateFlagResult.Updated(next)).run
      }
      .flatTap {
        case UpdateFlagResult.Updated(_) => invalidateAndPublishFlag(key)
        case _                           => IO.unit
      }

  def deleteFlag(command: DeleteFlagCommand): IO[DeleteFlagResult] =
    transactor
      .inTransaction {
        (for {
          now     <- Clock[IO].realTimeInstant.seal[DeleteFlagResult]
          entryId <- IdGenerator.generateId(AuditEntryId).seal
          flag    <- repository.findByKey(command.key, Lock.ForUpdate).valueOr[DeleteFlagResult](DeleteFlagResult.NotFound)
          entry = AuditEntry(entryId, flagId = None, flag.key, command.actor, AuditAction.Deleted, before = Some(flag), after = None, now)
          _ <- (auditRepository.append(entry) *> ruleRepository.deleteByFlagId(flag.id) *> repository.deleteById(flag.id)).seal
        } yield DeleteFlagResult.Deleted).run
      }
      .flatTap {
        case DeleteFlagResult.Deleted => invalidateAndPublishFlag(command.key)
        case _                        => IO.unit
      }

  def listFlags: IO[List[FeatureFlag]] = transactor.inSession(repository.findAll)

  def getFlag(key: FlagKey): IO[Option[FeatureFlag]] = transactor.inSession(repository.findByKey(key))

  def listAudit(key: FlagKey, page: PageRequest[AuditSortField]): IO[(List[AuditEntry], Long)] =
    transactor.inSession(auditRepository.listByFlagKey(key, page))

  def listSegments: IO[List[Segment]] = transactor.inSession(segmentRepository.findAll)

  def createSegment(command: CreateSegmentCommand): IO[CreateSegmentResult] =
    transactor
      .inTransaction {
        (for {
          segment <- buildSegment(command).seal[CreateSegmentResult]
          _ <- segmentRepository.insert(segment).seal[CreateSegmentResult].attempt {
                 case true  => Right(())
                 case false => Left(CreateSegmentResult.NameExists)
               }
        } yield CreateSegmentResult.Created(segment)).run
      }
      .flatTap {
        case CreateSegmentResult.Created(_) => invalidateAndPublishSegments
        case _                              => IO.unit
      }

  def updateSegment(command: UpdateSegmentCommand): IO[UpdateSegmentResult] =
    transactor
      .inTransaction {
        (for {
          now      <- Clock[IO].realTimeInstant.seal[UpdateSegmentResult]
          existing <- segmentRepository.findByName(command.name, Lock.ForUpdate).valueOr[UpdateSegmentResult](UpdateSegmentResult.NotFound)
          next = existing.copy(description = command.description, conditions = command.conditions, updatedAt = now)
          _ <- segmentRepository.update(next).seal
        } yield UpdateSegmentResult.Updated(next)).run
      }
      .flatTap {
        case UpdateSegmentResult.Updated(_) => invalidateAndPublishSegments
        case _                              => IO.unit
      }

  def deleteSegment(command: DeleteSegmentCommand): IO[DeleteSegmentResult] =
    transactor
      .inTransaction {
        (for {
          segment <- segmentRepository.findByName(command.name, Lock.ForUpdate).valueOr[DeleteSegmentResult](DeleteSegmentResult.NotFound)
          _       <- segmentRepository.deleteById(segment.id).seal
        } yield DeleteSegmentResult.Deleted).run
      }
      .flatTap {
        case DeleteSegmentResult.Deleted => invalidateAndPublishSegments
        case _                           => IO.unit
      }

  def evaluateForDebug(command: EvaluateFlagCommand): IO[Option[FlagEvaluationEngine.Result]] =
    IO(EvaluationContext(command.targetingKey, command.attributes.map { case (name, value) => AttributeName(name) -> AttributeValue(value) }))
      .flatMap { context =>
        transactor.inSession {
          repository.findByKey(command.key).flatMap {
            case None => IO.pure(None)
            case Some(flag) =>
              segmentRepository.findAll.map(segments => Some(FlagEvaluationEngine.evaluate(flag, segments.map(s => s.name -> s).toMap, context)))
          }
        }
      }

  def evaluateClientExposed(ctx: EvaluationContext): IO[Map[FlagKey, Json]] =
    (invalidationStarted *> cachedLoad(clientFlagsCache, (), transactor.inSession(repository.findAllClientExposed)))
      .flatMap(_.traverse(flag => fetchSegments(flag).map(segments => flag.key -> FlagEvaluationEngine.evaluate(flag, segments, ctx).value.toJson)))
      .map(_.toMap)

  private def buildFlag(command: CreateFlagCommand): IO[FeatureFlag] =
    for {
      id    <- IdGenerator.generateId(FlagId)
      now   <- Clock[IO].realTimeInstant
      rules <- buildRules(command.rules, now)
    } yield FeatureFlag(id, command.key, command.description, command.enabled, command.defaultValue, command.clientExposed, rules, now, now)

  private def buildRules(rules: List[RuleData], now: Instant): IO[List[Rule]] =
    rules.traverse(r => IdGenerator.generateId(RuleId).map(id => Rule(id, r.position, r.description, r.conditions, r.outcome, now)))

  private def buildSegment(command: CreateSegmentCommand): IO[Segment] =
    for {
      id  <- IdGenerator.generateId(SegmentId)
      now <- Clock[IO].realTimeInstant
    } yield Segment(id, command.name, command.description, command.conditions, now, now)

  private def invalidateAndPublishFlag(key: FlagKey): IO[Unit] =
    invalidateFlag(key) *> publishBestEffort(FeatureFlagEvent.Invalidated(key))

  private def invalidateAndPublishSegments: IO[Unit] =
    invalidateSegments *> publishBestEffort(FeatureFlagEvent.SegmentsChanged)

  private def validate(flag: FeatureFlag): Either[String, Unit] = {
    val expected   = flag.defaultValue.variantType
    val mismatched = flag.rules.filter(_.outcome.variant.variantType != expected)
    val duplicated = flag.rules.groupBy(_.position).collect { case (position, rules) if rules.sizeIs > 1 => position }
    if (mismatched.nonEmpty)
      Left(s"flag ${flag.key} is $expected but rules at positions ${mismatched.map(_.position).mkString(", ")} carry a different variant type")
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

final case class RuleData(
  position: RulePosition,
  description: FlagDescription,
  conditions: List[RuleCondition],
  outcome: RuleOutcome)

final case class CreateFlagCommand(
  key: FlagKey,
  description: FlagDescription,
  enabled: Boolean,
  defaultValue: FlagVariant,
  clientExposed: Boolean,
  rules: List[RuleData],
  actor: Actor)

final case class UpdateFlagCommand(
  key: FlagKey,
  description: FlagDescription,
  enabled: Boolean,
  defaultValue: FlagVariant,
  clientExposed: Boolean,
  rules: List[RuleData],
  actor: Actor)

final case class ToggleFlagCommand(
  key: FlagKey,
  enabled: Boolean,
  actor: Actor)

final case class DeleteFlagCommand(key: FlagKey, actor: Actor)

final case class EvaluateFlagCommand(
  key: FlagKey,
  targetingKey: TargetingKey,
  attributes: Map[String, String])

final case class CreateSegmentCommand(
  name: SegmentName,
  description: FlagDescription,
  conditions: List[RuleCondition])

final case class UpdateSegmentCommand(
  name: SegmentName,
  description: FlagDescription,
  conditions: List[RuleCondition])

final case class DeleteSegmentCommand(name: SegmentName)

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
