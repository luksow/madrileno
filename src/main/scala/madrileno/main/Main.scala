package madrileno.main

import cats.effect.{Clock, IO, IOApp, Resource}
import madrileno.utils.db.transactor.{PgConfig, PgTransactor}
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.server.middleware.EntityLimiter
import sttp.client4.httpclient.fs2.HttpClientFs2Backend
import pureconfig.*
import pl.iterators.stir.server.ToHttpRoutes

object Main extends IOApp.Simple {
  override def run: IO[Unit] =
    (for {
      config     <- Resource.eval(IO.delay(ConfigSource.default))
      httpClient <- HttpClientFs2Backend.resource[IO]()
      pgConfig   <- Resource.eval(IO.delay(config.at("pg").loadOrThrow[PgConfig]))
      transactor <- PgTransactor.resource(pgConfig)
      clock       = Clock[IO]
      application = ApplicationLoader(config, httpClient, transactor, clock)
      server <- EmberServerBuilder
                  .default[IO]
                  .withHost(application.httpConfig.host)
                  .withPort(application.httpConfig.port)
                  .withHttpApp(EntityLimiter.httpApp(application.routes.toHttpApp, application.httpConfig.maxRequestSize))
                  .build
    } yield application).use { app =>
      IO.never
    }
}
