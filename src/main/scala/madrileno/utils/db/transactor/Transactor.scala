package madrileno.utils.db.transactor

import cats.effect.IO
import skunk.{Session, Transaction}

trait Transactor {
  def inTransaction[A](f: (Session[IO], Transaction[IO]) => IO[A]): IO[A]

  def inSession[A](f: Session[IO] => IO[A]): IO[A]
}
