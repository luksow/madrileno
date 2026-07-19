package madrileno.utils.db.dsl

import cats.effect.IO
import skunk.*
import skunk.codec.all.*
import skunk.implicits.*

trait BaseRepository[T] {
  def baseFilter: Fragment[Void] = sql"1=1"
  val table: Table[T]

  def insertIfAbsent(a: T)(using session: Session[IO]): IO[Boolean] =
    session
      .option(sql"INSERT INTO ${table.n} (${table.*}) VALUES (${table.c}) ON CONFLICT DO NOTHING RETURNING 1".query(int4))(a)
      .map(_.isDefined)
}
