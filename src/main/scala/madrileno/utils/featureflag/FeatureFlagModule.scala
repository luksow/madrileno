package madrileno.utils.featureflag

import cats.effect.IO
import cats.effect.std.Supervisor
import com.softwaremill.macwire.*
import madrileno.auth.domain.AuthContext
import madrileno.utils.cache.CacheRuntime
import madrileno.utils.db.transactor.Transactor
import madrileno.utils.events.{EventBus, EventBusRuntime}
import madrileno.utils.featureflag.domain.FeatureFlagEvent
import madrileno.utils.featureflag.repositories.{FeatureFlagAuditRepository, FeatureFlagRepository, RuleRepository, SegmentRepository}
import madrileno.utils.featureflag.routers.FeatureFlagRouter
import madrileno.utils.featureflag.services.FeatureFlagServiceLive
import madrileno.utils.http.AuthRouteProvider
import madrileno.utils.observability.TelemetryContext
import pl.iterators.stir.server.Route

trait FeatureFlagModule extends AuthRouteProvider {
  given telemetryContext: TelemetryContext
  given supervisor: Supervisor[IO]
  val transactor: Transactor
  val cacheRuntime: CacheRuntime
  val eventBusRuntime: EventBusRuntime

  protected lazy val featureFlagEventBus: EventBus[FeatureFlagEvent] =
    eventBusRuntime.topic[FeatureFlagEvent]("feature_flag_events", maxQueued = 128)

  private lazy val ruleRepository             = wire[RuleRepository]
  private lazy val segmentRepository          = wire[SegmentRepository]
  private lazy val featureFlagRepository      = wire[FeatureFlagRepository]
  private lazy val featureFlagAuditRepository = wire[FeatureFlagAuditRepository]

  lazy val featureFlagService: FeatureFlagServiceLive = wire[FeatureFlagServiceLive]

  private lazy val featureFlagRouter = wire[FeatureFlagRouter]

  override abstract def route(auth: AuthContext): Route = {
    super.route(auth) ~ featureFlagRouter.authedRoutes(auth)
  }
}
