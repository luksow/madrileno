package madrileno.utils.db.dsl

import cats.effect.{Clock, IO}
import skunk.*
import skunk.implicits.*

import java.time.Instant

trait CreatedTimestampedTable {
  def createdAt: Column[Instant]
}

trait TimestampedTable extends CreatedTimestampedTable {
  def updatedAt: Column[Instant]
}

trait CreatedTimestampedRepository[A, Id](setCreatedAt: (A, Instant) => A) extends IdRepository[A, Id] {
  private def now = Clock[IO].realTimeInstant

  override def create(a: A)(session: Session[IO]): IO[Id] = {
    now.flatMap { instant =>
      val withCreatedAt = setCreatedAt(a, instant)
      super.create(withCreatedAt)(session)
    }
  }

  override def createAll(s: List[A])(session: Session[IO]): IO[List[Id]] =
    now.flatMap { instant =>
      val stamped = s.map(a => setCreatedAt(a, instant))
      super.createAll(stamped)(session)
    }

  protected val table: CreatedTimestampedTable & IdTable[A, Id] & Table[A]
}

trait TimestampedRepository[A, Id](setCreatedAt: (A, Instant) => A, setUpdatedAt: (A, Instant) => A)(using Clock[IO]) extends IdRepository[A, Id] {
  private def now = Clock[IO].realTimeInstant

  override def create(a: A)(session: Session[IO]): IO[Id] = {
    now.flatMap { instant =>
      val withCreatedAt = setCreatedAt(a, instant)
      val withUpdatedAt = setUpdatedAt(withCreatedAt, instant)
      super.create(withUpdatedAt)(session)
    }
  }

  override def createAll(s: List[A])(session: Session[IO]): IO[List[Id]] =
    now.flatMap { instant =>
      val stamped = s.map(a => setUpdatedAt(setCreatedAt(a, instant), instant))
      super.createAll(stamped)(session)
    }

  override def upsert(a: A)(session: Session[IO]): IO[Unit] = {
    now.flatMap { instant =>
      val withUpdatedAt             = setUpdatedAt(a, instant)
      val withCreatedAtAndUpdatedAt = setCreatedAt(withUpdatedAt, instant)
      val dataColumns               = table.columns.filterNot(c => c == table.createdAt || c == table.updatedAt)
      val fragments = dataColumns.map(col => sql"${col.n} = EXCLUDED.${col.n}") :+ sql"${table.updatedAt.n} = EXCLUDED.${table.updatedAt.n}"
      val updateFrag: Fragment[Void] = fragments match {
        case Nil          => sql""
        case head :: tail => tail.foldLeft(sql"${head}") { (acc, c) => sql"$acc, ${c}" }
      }
      println(sql"INSERT INTO ${table.n} (${table.*}) VALUES (${table.c}) ON CONFLICT (${table.id.n}) DO UPDATE SET $updateFrag")
      session
        .execute(sql"INSERT INTO ${table.n} (${table.*}) VALUES (${table.c}) ON CONFLICT (${table.id.n}) DO UPDATE SET $updateFrag".command)(
          withCreatedAtAndUpdatedAt
        )
        .void
    }
  }

  override def update(toBeUpdated: A)(session: Session[IO]): IO[Unit] = upsert(toBeUpdated)(session)

  // this can overwrite createdAt - it's callers responsibility to ensure that this is not the case
  override def updateById(id: Id, transform: A => A)(session: Session[IO]): IO[Unit] = {
    now.flatMap { instant =>
      super.updateById(id, transform.andThen(setUpdatedAt(_, instant)))(session)
    }
  }

  protected val table: TimestampedTable & IdTable[A, Id] & Table[A]
}
