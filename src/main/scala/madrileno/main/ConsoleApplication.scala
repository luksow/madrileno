package madrileno.main

import cats.effect.std.Supervisor
import cats.effect.unsafe.implicits.global
import cats.effect.{Clock, IO}
import io.opentelemetry.api.OpenTelemetry
import madrileno.utils.cache.CacheRuntime
import madrileno.utils.db.transactor.{PgConfig, PgTransactor}
import madrileno.utils.events.EventBusRuntime
import madrileno.utils.http.RateLimiterRuntime
import madrileno.utils.observability.TelemetryContext
import madrileno.utils.storage.{ObjectStoreRuntime, StorageConfig}
import madrileno.utils.task.{Scheduler, SchedulerConfig}
import org.typelevel.otel4s.metrics.Meter
import org.typelevel.otel4s.trace.Tracer
import pureconfig.ConfigSource
import sttp.client4.httpclient.fs2.HttpClientFs2Backend

// Boots an `ApplicationLoader` for `sbt console` use. Acquires the same runtime
// resources as `Main` (real DB pool, sttp HTTP client, scheduler, S3 object store,
// Postgres event bus) but synchronously via `.allocated.unsafeRunSync()._1`. The
// resources leak on JVM exit — fine for a console session.
//
// OpenTelemetry runs as noop (no traces / metrics emitted) so console queries
// don't clutter the dev OTel pipeline.
//
// Refuses to start unless `app.environment == Environment.Dev`. A prod-safe
// console (read-only by default + audit log + explicit confirmation) is a
// separate effort.
object ConsoleApplication {
  def boot(): ApplicationLoader = {
    val config    = ConfigSource.default
    val appConfig = config.at("app").loadOrThrow[AppConfig]

    require(
      appConfig.environment == Environment.Dev,
      s"ConsoleApplication refuses to boot with app.environment=${appConfig.environment}. " +
        "Set APP_ENVIRONMENT=dev. Prod console is a separate v2."
    )

    import org.typelevel.otel4s.metrics.Meter.Implicits.noop as meterNoop
    import org.typelevel.otel4s.trace.Tracer.Implicits.noop as tracerNoop
    given TelemetryContext = TelemetryContext(meterNoop, tracerNoop, OpenTelemetry.noop())

    val httpClient = HttpClientFs2Backend.resource[IO]().allocated.unsafeRunSync()._1

    val pgConfig   = config.at("pg").loadOrThrow[PgConfig]
    val transactor = PgTransactor.resource(pgConfig).allocated.unsafeRunSync()._1

    val schedulerConfig = config.at("scheduler").loadOrThrow[SchedulerConfig]
    val scheduler       = Scheduler(transactor, schedulerConfig)

    val cacheRuntime       = CacheRuntime.scaffeine
    val rateLimiterRuntime = RateLimiterRuntime.scaffeine()

    val storageConfig      = config.at("storage").loadOrThrow[StorageConfig]
    val objectStoreRuntime = ObjectStoreRuntime.s3(storageConfig).allocated.unsafeRunSync()._1

    given Supervisor[IO] = Supervisor[IO].allocated.unsafeRunSync()._1
    val eventBusRuntime  = EventBusRuntime.postgres(transactor)

    ApplicationLoader(
      config,
      httpClient,
      transactor,
      Clock[IO],
      scheduler.client,
      cacheRuntime,
      rateLimiterRuntime,
      objectStoreRuntime,
      eventBusRuntime
    )
  }
}
