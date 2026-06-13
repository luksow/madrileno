package madrileno.utils.featureflag.routers

import madrileno.utils.featureflag.domain.{Actor, AuditSortField, FlagKey, SegmentName}
import madrileno.utils.featureflag.routers.dto.*
import madrileno.utils.featureflag.services.*
import madrileno.utils.http.BaseRouter
import madrileno.utils.observability.TelemetryContext
import madrileno.utils.pagination.Page
import pl.iterators.stir.marshalling.ToResponseMarshallable
import pl.iterators.stir.server.Route

class FeatureFlagAdminRouter(service: FeatureFlagServiceLive)(using TelemetryContext) extends BaseRouter {

  private val actor = Actor("Admin")

  val routes: Route =
    pathPrefix("feature-flags") {
      (get & pathEndOrSingleSlash) {
        complete(service.listFlags.map[ToResponseMarshallable](flags => Ok -> flags.map(FeatureFlagDto(_))))
      } ~
        (post & pathEndOrSingleSlash & entity(as[CreateFlagRequest])) { request =>
          complete {
            service.createFlag(request.toCommand(actor)).map[ToResponseMarshallable] {
              case CreateFlagResult.Created(flag)    => Created -> FeatureFlagDto(flag)
              case CreateFlagResult.KeyExists        => error(Conflict, "flag-key-exists", s"Flag '${request.key}' already exists")
              case CreateFlagResult.Invalid(message) => error(BadRequest, "flag-invalid", message)
            }
          }
        } ~
        (get & path(Segment.as[FlagKey]) & pathEndOrSingleSlash) { key =>
          complete {
            service.getFlag(key).map[ToResponseMarshallable] {
              case Some(flag) => Ok -> FeatureFlagDto(flag)
              case None       => error(NotFound, "flag-not-found", s"No flag named '$key'")
            }
          }
        } ~
        (put & path(Segment.as[FlagKey]) & pathEndOrSingleSlash & entity(as[UpdateFlagRequest])) { (key, request) =>
          complete {
            service.updateFlag(request.toCommand(key, actor)).map[ToResponseMarshallable] {
              case UpdateFlagResult.Updated(flag)    => Ok -> FeatureFlagDto(flag)
              case UpdateFlagResult.NotFound         => error(NotFound, "flag-not-found", s"No flag named '$key'")
              case UpdateFlagResult.Invalid(message) => error(BadRequest, "flag-invalid", message)
            }
          }
        } ~
        (post & path(Segment.as[FlagKey] / "toggle") & pathEndOrSingleSlash & entity(as[ToggleFlagRequest])) { (key, request) =>
          complete {
            service.toggleFlag(ToggleFlagCommand(key, request.enabled, actor)).map[ToResponseMarshallable] {
              case UpdateFlagResult.Updated(flag)    => Ok -> FeatureFlagDto(flag)
              case UpdateFlagResult.NotFound         => error(NotFound, "flag-not-found", s"No flag named '$key'")
              case UpdateFlagResult.Invalid(message) => error(BadRequest, "flag-invalid", message)
            }
          }
        } ~
        (delete & path(Segment.as[FlagKey]) & pathEndOrSingleSlash) { key =>
          complete {
            service.deleteFlag(DeleteFlagCommand(key, actor)).map[ToResponseMarshallable] {
              case DeleteFlagResult.Deleted  => NoContent
              case DeleteFlagResult.NotFound => error(NotFound, "flag-not-found", s"No flag named '$key'")
            }
          }
        } ~
        (get & path(Segment.as[FlagKey] / "audit") & pathEndOrSingleSlash & paginated(AuditSortField.CreatedAt)) { (key, page) =>
          complete {
            service.listAudit(key, page).map[ToResponseMarshallable] { case (entries, total) =>
              Ok -> Page(entries.map(AuditEntryDto(_)), total, page.limitValue, page.offsetValue)
            }
          }
        } ~
        (post & path(Segment.as[FlagKey] / "evaluate") & pathEndOrSingleSlash & entity(as[EvaluateFlagRequest])) { (key, request) =>
          complete {
            service.evaluateForDebug(EvaluateFlagCommand(key, request.targetingKey, request.attributes)).map[ToResponseMarshallable] {
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
              service.createSegment(CreateSegmentCommand(request.name, request.description, request.conditions)).map[ToResponseMarshallable] {
                case CreateSegmentResult.Created(segment) => Created -> SegmentDto(segment)
                case CreateSegmentResult.NameExists       => error(Conflict, "segment-name-exists", s"Segment '${request.name}' already exists")
              }
            }
          } ~
          (put & path(Segment.as[SegmentName]) & pathEndOrSingleSlash & entity(as[UpdateSegmentRequest])) { (name, request) =>
            complete {
              service.updateSegment(UpdateSegmentCommand(name, request.description, request.conditions)).map[ToResponseMarshallable] {
                case UpdateSegmentResult.Updated(segment) => Ok -> SegmentDto(segment)
                case UpdateSegmentResult.NotFound         => error(NotFound, "segment-not-found", s"No segment named '$name'")
              }
            }
          } ~
          (delete & path(Segment.as[SegmentName]) & pathEndOrSingleSlash) { name =>
            complete {
              service.deleteSegment(DeleteSegmentCommand(name)).map[ToResponseMarshallable] {
                case DeleteSegmentResult.Deleted  => NoContent
                case DeleteSegmentResult.NotFound => error(NotFound, "segment-not-found", s"No segment named '$name'")
              }
            }
          }
      }
}
