package madrileno.utils.db.dsl

import cats.effect.{Clock, IO}
import skunk.*
import skunk.codec.all.*
import skunk.implicits.*

import java.time.Instant

trait SoftDeleteTable {
  def deletedAt: Column[Option[Instant]]
}

trait SoftDeleteRepository[A, Id](using Clock[IO]) extends IdRepository[A, Id] {
  private def now = Clock[IO].realTimeInstant

  override def baseFilter: Fragment[Void] = sql"${super.baseFilter} AND ${table.deletedAt.n} IS NULL"

  def softDeleteById(id: Id)(session: Session[IO]): IO[Unit] = {
    now.flatMap { instant =>
      session
        .execute(sql"UPDATE ${table.n} SET ${table.deletedAt.n} = ${table.deletedAt.c} WHERE ${table.id.n} = ${table.id.c}".command)(
          (Some(instant), id)
        )
        .void
    }
  }

  def restoreById(id: Id)(session: Session[IO]): IO[Unit] = {
    session
      .execute(sql"UPDATE ${table.n} SET ${table.deletedAt.n} = NULL WHERE ${table.id.n} = ${table.id.c}".command)(id)
      .void
  }

  def softDeleteByIds(ids: List[Id])(session: Session[IO]): IO[Unit] = {
    now.flatMap { instant =>
      session
        .execute(sql"UPDATE ${table.n} SET ${table.deletedAt.n} = ${table.deletedAt.c} WHERE ${table.id.n} IN (${table.id.c.list(ids)})".command)(
          (Some(instant), ids)
        )
        .void
    }
  }

  def softDeleteAll(session: Session[IO]): IO[Unit] = {
    now.flatMap { instant =>
      session
        .execute(sql"UPDATE ${table.n} SET ${table.deletedAt.n} = ${table.deletedAt.c}".command)(Some(instant))
        .void
    }
  }

  def restoreByIds(ids: List[Id])(session: Session[IO]): IO[Unit] = {
    session
      .execute(sql"UPDATE ${table.n} SET ${table.deletedAt.n} = NULL WHERE ${table.id.n} IN (${table.id.c.list(ids)})".command)(ids)
      .void
  }

  def findByIdWithDeleted(id: Id)(session: Session[IO]): IO[Option[A]] = {
    session.option(sql"SELECT ${table.*} FROM ${table.n} WHERE ${table.id.n} = ${table.id.c} AND ${super.baseFilter}".query(table.c))(id)
  }

  def existsByIdWithDeleted(id: Id)(session: Session[IO]): IO[Boolean] = {
    session
      .option(sql"SELECT 1 FROM ${table.n} WHERE ${table.id.n} = ${table.id.c} AND ${super.baseFilter}".query(int4))(id)
      .map(_.isDefined)
  }

  def findByIdsWithDeleted(ids: List[Id])(session: Session[IO]): IO[List[A]] = {
    session
      .execute(sql"SELECT ${table.*} FROM ${table.n} WHERE ${table.id.n} IN (${table.id.c.list(ids)}) AND ${super.baseFilter}".query(table.c))(ids)
  }

  def findWithDeleted(id: Id)(session: Session[IO]): IO[A] = {
    session.unique(sql"SELECT ${table.*} FROM ${table.n} WHERE ${table.id.n} = ${table.id.c} AND ${super.baseFilter}".query(table.c))(id)
  }

  def allWithDeleted(session: Session[IO]): IO[List[A]] = {
    session.execute(sql"SELECT ${table.*} FROM ${table.n} WHERE ${super.baseFilter}".query(table.c))
  }

  def countWithDeleted(session: Session[IO]): IO[Long] = {
    session.unique(sql"SELECT COUNT(*) FROM ${table.n} WHERE ${super.baseFilter}".query(int8))
  }

  def purgeDeletedBefore(instant: Instant)(session: Session[IO]): IO[Unit] = {
    session
      .execute(sql"DELETE FROM ${table.n} WHERE ${table.deletedAt.n} < ${table.deletedAt.c}".command)(Some(instant))
      .void
  }

  protected val table: SoftDeleteTable & IdTable[A, Id] & Table[A]
}
