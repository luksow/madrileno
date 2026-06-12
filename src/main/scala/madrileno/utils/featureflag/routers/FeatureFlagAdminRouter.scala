package madrileno.utils.featureflag.routers

import cats.effect.{Clock, IO}
import cats.syntax.all.*
import madrileno.utils.crypto.IdGenerator
import madrileno.utils.featureflag.domain.*
import madrileno.utils.featureflag.domain.Segment as FlagSegment
import madrileno.utils.featureflag.routers.dto.*
import madrileno.utils.featureflag.services.*
import madrileno.utils.http.BaseRouter
import madrileno.utils.observability.TelemetryContext
import madrileno.utils.pagination.Page
import pl.iterators.stir.marshalling.ToResponseMarshallable
import pl.iterators.stir.server.Route

import java.time.Instant

class FeatureFlagAdminRouter(service: FeatureFlagServiceLive, actor: Actor)(using TelemetryContext) extends BaseRouter {

  val routes: Route =
    pathPrefix("feature-flags") {
      (get & pathEndOrSingleSlash) {
        complete(service.listFlags.map[ToResponseMarshallable](flags => Ok -> flags.map(FeatureFlagDto(_))))
      } ~
        (post & pathEndOrSingleSlash & entity(as[CreateFlagRequest])) { request =>
          complete {
            buildFlag(request).flatMap(service.createFlag(_, actor)).map[ToResponseMarshallable] {
              case CreateFlagResult.Created(flag)    => Created -> FeatureFlagDto(flag)
              case CreateFlagResult.KeyExists        => error(Conflict, "flag-key-exists", s"Flag '${request.key}' already exists")
              case CreateFlagResult.Invalid(message) => error(BadRequest, "flag-invalid", message)
            }
          }
        } ~
        (get & path(Segment) & pathEndOrSingleSlash) { key =>
          complete {
            service.getFlag(FlagKey(key)).map[ToResponseMarshallable] {
              case Some(flag) => Ok -> FeatureFlagDto(flag)
              case None       => error(NotFound, "flag-not-found", s"No flag named '$key'")
            }
          }
        } ~
        (put & path(Segment) & pathEndOrSingleSlash & entity(as[UpdateFlagRequest])) { (key, request) =>
          complete {
            Clock[IO].realTimeInstant
              .flatMap(now => buildRules(request.rules, now))
              .flatMap { rules =>
                service.updateFlag(
                  FlagKey(key),
                  _.copy(
                    description = request.description,
                    enabled = request.enabled,
                    defaultValue = request.defaultValue,
                    clientExposed = request.clientExposed,
                    rules = rules
                  ),
                  actor
                )
              }
              .map[ToResponseMarshallable] {
                case UpdateFlagResult.Updated(flag)    => Ok -> FeatureFlagDto(flag)
                case UpdateFlagResult.NotFound         => error(NotFound, "flag-not-found", s"No flag named '$key'")
                case UpdateFlagResult.Invalid(message) => error(BadRequest, "flag-invalid", message)
              }
          }
        } ~
        (post & path(Segment / "toggle") & pathEndOrSingleSlash & entity(as[ToggleFlagRequest])) { (key, request) =>
          complete {
            service.toggleFlag(FlagKey(key), request.enabled, actor).map[ToResponseMarshallable] {
              case UpdateFlagResult.Updated(flag)    => Ok -> FeatureFlagDto(flag)
              case UpdateFlagResult.NotFound         => error(NotFound, "flag-not-found", s"No flag named '$key'")
              case UpdateFlagResult.Invalid(message) => error(BadRequest, "flag-invalid", message)
            }
          }
        } ~
        (delete & path(Segment) & pathEndOrSingleSlash) { key =>
          complete {
            service.deleteFlag(FlagKey(key), actor).map[ToResponseMarshallable] {
              case DeleteFlagResult.Deleted  => NoContent
              case DeleteFlagResult.NotFound => error(NotFound, "flag-not-found", s"No flag named '$key'")
            }
          }
        } ~
        (get & path(Segment / "audit") & pathEndOrSingleSlash & paginated(AuditSortField.CreatedAt)) { (key, page) =>
          complete {
            service.listAudit(FlagKey(key), page).map[ToResponseMarshallable] { case (entries, total) =>
              Ok -> Page(entries.map(AuditEntryDto(_)), total, page.limitValue, page.offsetValue)
            }
          }
        } ~
        (post & path(Segment / "evaluate") & pathEndOrSingleSlash & entity(as[EvaluateFlagRequest])) { (key, request) =>
          complete {
            val ctx =
              EvaluationContext(request.targetingKey, request.attributes.map { case (name, value) => AttributeName(name) -> AttributeValue(value) })
            service.evaluateForDebug(FlagKey(key), ctx).map[ToResponseMarshallable] {
              case Some(result) => Ok -> EvaluationResultDto(result.value.toJson, result.reason)
              case None         => error(NotFound, "flag-not-found", s"No flag named '$key'")
            }
          }
        }
    } ~
      pathPrefix("feature-flag-segments") {
        (get & pathEndOrSingleSlash) {
          complete(service.listSegments.map[ToResponseMarshallable](segments => Ok -> segments.map(SegmentDto(_))))
        } ~
          (post & pathEndOrSingleSlash & entity(as[CreateSegmentRequest])) { request =>
            complete {
              buildSegment(request).flatMap(service.createSegment).map[ToResponseMarshallable] {
                case CreateSegmentResult.Created(segment) => Created -> SegmentDto(segment)
                case CreateSegmentResult.NameExists       => error(Conflict, "segment-name-exists", s"Segment '${request.name}' already exists")
              }
            }
          } ~
          (put & path(Segment) & pathEndOrSingleSlash & entity(as[UpdateSegmentRequest])) { (name, request) =>
            complete {
              service
                .updateSegment(SegmentName(name), _.copy(description = request.description, conditions = request.conditions))
                .map[ToResponseMarshallable] {
                  case UpdateSegmentResult.Updated(segment) => Ok -> SegmentDto(segment)
                  case UpdateSegmentResult.NotFound         => error(NotFound, "segment-not-found", s"No segment named '$name'")
                }
            }
          } ~
          (delete & path(Segment) & pathEndOrSingleSlash) { name =>
            complete {
              service.deleteSegment(SegmentName(name)).map[ToResponseMarshallable] {
                case DeleteSegmentResult.Deleted  => NoContent
                case DeleteSegmentResult.NotFound => error(NotFound, "segment-not-found", s"No segment named '$name'")
              }
            }
          }
      }

  private def buildFlag(request: CreateFlagRequest): IO[FeatureFlag] =
    for {
      id    <- IdGenerator.generateId(FlagId)
      now   <- Clock[IO].realTimeInstant
      rules <- buildRules(request.rules, now)
    } yield FeatureFlag(
      id = id,
      key = request.key,
      description = request.description,
      enabled = request.enabled,
      defaultValue = request.defaultValue,
      clientExposed = request.clientExposed,
      rules = rules,
      createdAt = now,
      updatedAt = now
    )

  private def buildRules(requests: List[RuleRequest], now: Instant): IO[List[Rule]] =
    requests.traverse(r => IdGenerator.generateId(RuleId).map(id => Rule(id, r.position, r.description, r.conditions, r.outcome, now)))

  private def buildSegment(request: CreateSegmentRequest): IO[FlagSegment] =
    for {
      id  <- IdGenerator.generateId(SegmentId)
      now <- Clock[IO].realTimeInstant
    } yield FlagSegment(id, request.name, request.description, request.conditions, now, now)
}
