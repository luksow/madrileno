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

  override def findById(id: Id)(session: Session[IO]): IO[Option[A]] = {
    session.option(sql"SELECT ${table.*} FROM ${table.n} WHERE ${table.id.n} = ${table.id.c} AND ${table.deletedAt.n} IS NULL".query(table.c))(id)
  }

  def findByIdWithDeleted(id: Id)(session: Session[IO]): IO[Option[A]] = {
    session.option(sql"SELECT ${table.*} FROM ${table.n} WHERE ${table.id.n} = ${table.id.c}".query(table.c))(id)
  }

  override def existsById(id: Id)(session: Session[IO]): IO[Boolean] = {
    session
      .option(sql"SELECT 1 FROM ${table.n} WHERE ${table.id.n} = ${table.id.c} AND ${table.deletedAt.n} IS NULL".query(int4))(id)
      .map(_.isDefined)
  }

  def existsByIdWithDeleted(id: Id)(session: Session[IO]): IO[Boolean] = {
    session
      .option(sql"SELECT 1 FROM ${table.n} WHERE ${table.id.n} = ${table.id.c}".query(int4))(id)
      .map(_.isDefined)
  }

  override def findByIds(ids: List[Id])(session: Session[IO]): IO[List[A]] = {
    session.execute(
      sql"SELECT ${table.*} FROM ${table.n} WHERE ${table.id.n} IN (${table.id.c.list(ids)}) AND ${table.deletedAt.n} IS NULL".query(table.c)
    )(ids)
  }

  def findByIdsWithDeleted(ids: List[Id])(session: Session[IO]): IO[List[A]] = {
    session.execute(sql"SELECT ${table.*} FROM ${table.n} WHERE ${table.id.n} IN (${table.id.c.list(ids)})".query(table.c))(ids)
  }

  override def find(id: Id)(session: Session[IO]): IO[A] = {
    session.unique(sql"SELECT ${table.*} FROM ${table.n} WHERE ${table.id.n} = ${table.id.c} AND ${table.deletedAt.n} IS NULL".query(table.c))(id)
  }

  def findWithDeleted(id: Id)(session: Session[IO]): IO[A] = {
    session.unique(sql"SELECT ${table.*} FROM ${table.n} WHERE ${table.id.n} = ${table.id.c}".query(table.c))(id)
  }

  override def all(session: Session[IO]): IO[List[A]] = {
    session.execute(sql"SELECT ${table.*} FROM ${table.n} WHERE ${table.deletedAt.n} IS NULL".query(table.c))
  }

  def allWithDeleted(session: Session[IO]): IO[List[A]] = {
    session.execute(sql"SELECT ${table.*} FROM ${table.n}".query(table.c))
  }

  override def count(session: Session[IO]): IO[Long] = {
    session.unique(sql"SELECT COUNT(*) FROM ${table.n} WHERE ${table.deletedAt.n} IS NULL".query(int8))
  }

  def countWithDeleted(session: Session[IO]): IO[Long] = {
    session.unique(sql"SELECT COUNT(*) FROM ${table.n}".query(int8))
  }

  def purgeDeletedBefore(instant: Instant)(session: Session[IO]): IO[Unit] = {
    session
      .execute(sql"DELETE FROM ${table.n} WHERE ${table.deletedAt.n} < ${table.deletedAt.c}".command)(Some(instant))
      .void
  }

  protected val table: SoftDeleteTable & IdTable[A, Id] & Table[A]
}
