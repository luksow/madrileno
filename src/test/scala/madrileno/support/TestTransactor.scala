package madrileno.support

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import com.dimafeng.testcontainers.PostgreSQLContainer
import com.dimafeng.testcontainers.scalatest.TestContainersForAll
import madrileno.utils.db.transactor.{DB, DBInTransaction, PgConfig, PgTransactor, Transactor}
import org.flywaydb.core.Flyway
import org.scalatest.Suite
import org.typelevel.otel4s.trace.Tracer
import skunk.Session

trait TestTransactor extends TestContainersForAll { self: Suite =>

  override type Containers = PostgreSQLContainer

  override def startContainers(): PostgreSQLContainer = {
    val container = PostgreSQLContainer.Def().start()

    Flyway
      .configure()
      .dataSource(container.jdbcUrl, container.username, container.password)
      .load()
      .migrate()

    container
  }

  given Tracer[IO] = Tracer.noop[IO]

  private lazy val pgTransactor: PgTransactor = withContainers { container =>
    val pgConfig = PgConfig(
      host = container.host,
      port = container.mappedPort(5432),
      user = container.username,
      database = container.databaseName,
      password = Some(container.password)
    )
    // Finalizer intentionally discarded — Testcontainers kills the PG container on JVM exit
    PgTransactor.resource(pgConfig).allocated.unsafeRunSync()._1
  }

  lazy val transactor: Transactor = pgTransactor

  def withSession[A](f: DB[A]): IO[A] =
    transactor.inSession(f)

  def withRollback[A](f: DBInTransaction[A]): IO[A] =
    transactor.inSession {
      val session = summon[Session[IO]]
      session.transaction.use { tx =>
        given skunk.Transaction[IO] = tx
        f.flatTap(_ => tx.rollback)
      }
    }
}
