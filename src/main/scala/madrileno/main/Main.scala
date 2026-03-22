package madrileno.main

import cats.effect.{Clock, IO, IOApp, Resource}
import io.opentelemetry.instrumentation.logback.appender.v1_0.OpenTelemetryAppender
import madrileno.utils.db.transactor.{PgConfig, PgTransactor}
import madrileno.utils.observability.TelemetryContext
import madrileno.utils.task.{Schedule, Scheduler, SchedulerConfig, Task, TaskDescriptor}
import org.http4s.RequestPrelude
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.otel4s.middleware.metrics.OtelMetrics
import org.http4s.otel4s.middleware.server.RouteClassifier
import org.http4s.otel4s.middleware.trace.redact.{HeaderRedactor, PathRedactor, QueryRedactor}
import org.http4s.otel4s.middleware.trace.server.{ServerMiddleware, ServerSpanDataProvider}
import org.http4s.server.middleware.{EntityLimiter, Metrics}
import org.typelevel.otel4s.metrics.{Meter, MeterProvider}
import org.typelevel.otel4s.oteljava.OtelJava
import org.typelevel.otel4s.trace.{Tracer, TracerProvider}
import pl.iterators.stir.server.ToHttpRoutes
import pureconfig.*
import sttp.client4.httpclient.fs2.HttpClientFs2Backend
import scala.concurrent.duration.*

object Main extends IOApp.Simple {
  override def run: IO[Unit] =
    (for {
      config    <- Resource.eval(IO.delay(ConfigSource.default))
      appConfig <- Resource.eval(IO.delay(config.at("app").loadOrThrow[AppConfig]))
      otel      <- OtelJava.autoConfigured[IO]()
      given TracerProvider[IO] = otel.tracerProvider
      given Tracer[IO] <- Resource.eval(summon[TracerProvider[IO]].get(appConfig.name))
      given MeterProvider[IO] = otel.meterProvider
      given Meter[IO] <- Resource.eval(summon[MeterProvider[IO]].get(appConfig.name))
      given TelemetryContext = TelemetryContext(Meter[IO], Tracer[IO], otel.underlying)
      _                      = OpenTelemetryAppender.install(otel.underlying)
      httpClient <- HttpClientFs2Backend.resource[IO]()
      pgConfig   <- Resource.eval(IO.delay(config.at("pg").loadOrThrow[PgConfig]))
      transactor <- PgTransactor.resource(pgConfig)
      clock = Clock[IO]
      startTasks = List(Task.recurring("my-every-second-task", Schedule.RecurringWithFixedRate(1.second)) { _ =>
                     IO.println("Every second task executed")
                   })
      randomCustomTask = Task.custom(TaskDescriptor[Int]("random-number-task")) { number =>
                           IO.println(s"Custom task fired with number=$number") *> {
                             val nextNumber  = scala.util.Random.nextInt(100)
                             val nextSeconds = scala.util.Random.nextInt(20)
                             val nextAt      = java.time.Instant.now().plusSeconds(nextSeconds.toLong)
                             IO.println(s"  -> next run in ${nextSeconds}s with number=$nextNumber") *>
                               IO.pure(Schedule.NextAt(nextAt, nextNumber))
                           }
                         }
      schedulerConfig <- Resource.eval(IO.delay(config.at("scheduler").loadOrThrow[SchedulerConfig]))
      scheduler       <- Scheduler(transactor, schedulerConfig).run(startTasks, customTasks = List(randomCustomTask))
      _         <- Resource.eval(scheduler.schedule(randomCustomTask.instance("random-1", payload = 42, firstAt = java.time.Instant.now())))
      application = ApplicationLoader(config, httpClient, transactor, clock)
      metricsOps <- OtelMetrics.serverMetricsOps[IO]().toResource
      redactor = new QueryRedactor.NeverRedact with PathRedactor.NeverRedact
      routeClassifier = new RouteClassifier {
                          override def classify(request: RequestPrelude): Option[String] = {
                            val classified = request.uri.path.segments.foldLeft("") { (acc, seg) =>
                              if (seg.encoded.matches("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$"))
                                acc + "/{uuid}"
                              else if (seg.encoded.forall(_.isDigit)) acc + "/{id}"
                              else acc + s"/${seg.encoded}"
                            }
                            Some(classified)
                          }
                        }
      serverMiddleware <- ServerMiddleware
                            .builder[IO](
                              ServerSpanDataProvider
                                .openTelemetry(redactor)
                                .withRouteClassifier(routeClassifier)
                                .optIntoClientPort
                                .optIntoHttpRequestHeaders(HeaderRedactor.default)
                                .optIntoHttpResponseHeaders(HeaderRedactor.default)
                            )
                            .build
                            .toResource
      httpApp =
        serverMiddleware.wrapHttpApp(
          EntityLimiter.httpApp(Metrics(metricsOps)(application.routes.toHttpRoutes).orNotFound, application.httpConfig.maxRequestSize)
        )
      _ <- EmberServerBuilder
             .default[IO]
             .withHost(application.httpConfig.host)
             .withPort(application.httpConfig.port)
             .withHttpApp(httpApp)
             .build
    } yield application).use { _ =>
      IO.never
    }
}
