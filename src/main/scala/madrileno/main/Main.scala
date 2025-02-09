package madrileno.main

import cats.effect.{Clock, IO, IOApp, Resource}
import io.opentelemetry.instrumentation.logback.appender.v1_0.OpenTelemetryAppender
import madrileno.utils.db.transactor.{PgConfig, PgTransactor}
import madrileno.utils.observability.TelemetryContext
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.otel4s.middleware.metrics.OtelMetrics
import org.http4s.otel4s.middleware.trace.server.ServerMiddleware
import org.http4s.server.middleware.{EntityLimiter, Metrics}
import org.typelevel.otel4s.metrics.Meter
import org.typelevel.otel4s.oteljava.OtelJava
import org.typelevel.otel4s.trace.Tracer
import sttp.client4.httpclient.fs2.HttpClientFs2Backend
import pureconfig.*
import pl.iterators.stir.server.ToHttpRoutes

object Main extends IOApp.Simple {
  override def run: IO[Unit] =
    (for {
      config           <- Resource.eval(IO.delay(ConfigSource.default))
      appConfig        <- Resource.eval(IO.delay(config.at("app").loadOrThrow[AppConfig]))
      otel             <- OtelJava.autoConfigured[IO]()
      given Tracer[IO] <- Resource.eval(otel.tracerProvider.get(appConfig.name))
      given Meter[IO]  <- Resource.eval(otel.meterProvider.get(appConfig.name))
      given TelemetryContext = TelemetryContext(Meter[IO], Tracer[IO], otel.underlying)
      _                      = OpenTelemetryAppender.install(otel.underlying)
      httpClient <- HttpClientFs2Backend.resource[IO]()
      pgConfig   <- Resource.eval(IO.delay(config.at("pg").loadOrThrow[PgConfig]))
      transactor <- PgTransactor.resource(pgConfig)
      clock       = Clock[IO]
      application = ApplicationLoader(config, httpClient, transactor, clock)
      metricsOps <- OtelMetrics.serverMetricsOps[IO]().toResource
      httpApp =
        ServerMiddleware
          .default[IO]
          .withServerSpanName(p => s"Http Server - ${p.method} /${p.uri.path.segments.take(2).mkString("/")}")
          .buildHttpApp {
            EntityLimiter.httpApp(Metrics(metricsOps)(application.routes.toHttpRoutes).orNotFound, application.httpConfig.maxRequestSize)
          }
      server <- EmberServerBuilder
                  .default[IO]
                  .withHost(application.httpConfig.host)
                  .withPort(application.httpConfig.port)
                  .withHttpApp(httpApp)
                  .build
    } yield application).use { app =>
      IO.never
    }
}
