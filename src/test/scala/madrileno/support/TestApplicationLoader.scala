package madrileno.support

import cats.effect.unsafe.implicits.global
import cats.effect.{Clock, IO}
import com.dimafeng.testcontainers.PostgreSQLContainer
import com.dimafeng.testcontainers.scalatest.TestContainersForAll
import madrileno.auth.domain.AuthContext
import madrileno.auth.services.{ExternalAuthVerifier, JwtService}
import madrileno.main.ApplicationLoader
import madrileno.utils.db.transactor.{PgConfig, PgTransactor}
import madrileno.utils.observability.TelemetryContext
import madrileno.utils.task.{Scheduler, SchedulerConfig}
import org.flywaydb.core.Flyway
import org.scalatest.Suite
import org.typelevel.otel4s.metrics.Meter
import org.typelevel.otel4s.trace.Tracer
import pureconfig.ConfigSource
import sttp.client4.httpclient.fs2.HttpClientFs2Backend

import java.time.Instant

trait TestApplicationLoader extends TestContainersForAll with TestMailpit { self: Suite =>

  override type Containers = PostgreSQLContainer

  override def startContainers(): PostgreSQLContainer = {
    val container = PostgreSQLContainer.Def().start()
    Flyway.configure().dataSource(container.jdbcUrl, container.username, container.password).load().migrate()
    container
  }

  given Tracer[IO]       = Tracer.noop[IO]
  given Meter[IO]        = Meter.noop[IO]
  given TelemetryContext = TelemetryContext(Meter.noop[IO], Tracer.noop[IO], io.opentelemetry.api.OpenTelemetry.noop())

  lazy val application: ApplicationLoader = withContainers { container =>
    val pgConfig = PgConfig(
      host = container.host,
      port = container.mappedPort(5432),
      user = container.username,
      database = container.databaseName,
      password = Some(container.password)
    )
    val transactor      = PgTransactor.resource(pgConfig).allocated.unsafeRunSync()._1
    val httpClient      = HttpClientFs2Backend.resource[IO]().allocated.unsafeRunSync()._1
    val config          = ConfigSource.default
    val schedulerConfig = SchedulerConfig()
    val scheduler       = Scheduler(transactor, schedulerConfig)
    new ApplicationLoader(config, httpClient, transactor, Clock[IO], scheduler.client) {
      override protected lazy val externalAuthVerifier: ExternalAuthVerifier =
        FakeAuthVerifier(TestData.verifiedExternalToken())
    }
  }

  private val config                             = ConfigSource.default
  private val jwtConfig: JwtService.Config       = config.at("jwt").loadOrThrow[JwtService.Config]
  val jwtService: JwtService                     = JwtService(jwtConfig)
  def validJwt(authContext: AuthContext): String = jwtService.encode(authContext, Instant.now()).unwrap
}
