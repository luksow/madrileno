package madrileno.utils.db.transactor

import cats.effect.IO
import skunk.*
import skunk.util.Origin

type DB[A]              = Session[IO] ?=> IO[A]
type DBInTransaction[A] = Session[IO] ?=> Transaction[IO] ?=> IO[A]

trait Transactor {
  def inTransaction[A](f: DBInTransaction[A]): IO[A]

  def savepoint(using
    transaction: Transaction[IO],
    origin: Origin
  ): IO[transaction.Savepoint] = {
    transaction.savepoint
  }

  def inSession[A](f: DB[A]): IO[A]
}
