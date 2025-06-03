package madrileno.utils.db.dsl

import cats.effect.IO
import skunk.*
import skunk.codec.all.*
import skunk.implicits.*

trait IdTable[A, Id] {
  def id: Column[Id]
}

trait IdRepository[A, Id](getId: A => Id) extends BaseRepository[A] {
  def create(a: A)(session: Session[IO]): IO[Id] =
    session.unique(sql"INSERT INTO ${table.n} (${table.*}) VALUES (${table.c}) RETURNING ${table.id.n}".query(table.id.c))(a)

  def createAll(s: List[A])(session: Session[IO]): IO[List[Id]] = {
    val enc = table.c.values.list(s)
    session.execute(sql"INSERT INTO ${table.n} (${table.*}) VALUES $enc RETURNING ${table.id.n}".query(table.id.c))(s)
  }

  def upsert(a: A)(session: Session[IO]): IO[Unit] =
    session
      .execute(
        sql"INSERT INTO ${table.n} (${table.*}) VALUES (${table.c}) ON CONFLICT (${table.id.n}) DO UPDATE SET (${table.*}) = (${table.c})".command
      )((a, a))
      .void

  def findById(id: Id)(session: Session[IO]): IO[Option[A]] =
    session.option(sql"SELECT ${table.*} FROM ${table.n} WHERE $baseFilter AND ${table.id.n} = ${table.id.c}".query(table.c))(id)

  def existsById(id: Id)(session: Session[IO]): IO[Boolean] =
    session
      .option(sql"SELECT 1 FROM ${table.n} WHERE $baseFilter AND ${table.id.n} = ${table.id.c}".query(int4))(id)
      .map(_.isDefined)

  def findByIds(ids: List[Id])(session: Session[IO]): IO[List[A]] =
    session.execute(sql"SELECT ${table.*} FROM ${table.n} WHERE $baseFilter AND ${table.id.n} IN (${table.id.c.list(ids)})".query(table.c))(ids)

  def getById(id: Id)(session: Session[IO]): IO[A] =
    session.unique(sql"SELECT ${table.*} FROM ${table.n} WHERE $baseFilter AND ${table.id.n} = ${table.id.c}".query(table.c))(id)

  def all(session: Session[IO]): IO[List[A]] =
    session.execute(sql"SELECT ${table.*} FROM ${table.n} WHERE $baseFilter".query(table.c))

  def count(session: Session[IO]): IO[Long] =
    session.unique(sql"SELECT COUNT(*) FROM ${table.n} WHERE $baseFilter".query(int8))

  def update(toBeUpdated: A)(session: Session[IO]): IO[Unit] =
    session
      .execute(sql"UPDATE ${table.n} SET (${table.*}) = (${table.c}) WHERE ${table.id.n} = ${table.id.c}".command)((toBeUpdated, getId(toBeUpdated)))
      .void

  def updateById(id: Id, transform: A => A)(session: Session[IO]): IO[Unit] = {
    session.transaction.use { tx =>
      session.option(sql"SELECT ${table.*} FROM ${table.n} WHERE ${table.id.n} = ${table.id.c} FOR UPDATE".query(table.c))(id).flatMap {
        case Some(obj) =>
          val toBeUpdated = transform(obj)
          session
            .execute(sql"UPDATE ${table.n} SET (${table.*}) = (${table.c}) WHERE ${table.id.n} = ${table.id.c}".command)(
              (toBeUpdated, getId(toBeUpdated))
            )
            .void
        case None => IO.unit
      }
    }
  }

  def deleteById(id: Id)(session: Session[IO]): IO[Unit] =
    session
      .execute(sql"DELETE FROM ${table.n} WHERE ${table.id.n} = ${table.id.c}".command)(id)
      .void

  def deleteByIds(ids: List[Id])(session: Session[IO]): IO[Unit] =
    session
      .execute(sql"DELETE FROM ${table.n} WHERE ${table.id.n} IN (${table.id.c.list(ids)})".command)(ids)
      .void

  def delete(a: A)(session: Session[IO]): IO[Unit] = deleteById(getId(a))(session)

  def deleteAll(session: Session[IO]): IO[Unit] =
    session.execute(sql"DELETE FROM ${table.n}".command).void

  protected val table: IdTable[A, Id] & Table[A]
}
