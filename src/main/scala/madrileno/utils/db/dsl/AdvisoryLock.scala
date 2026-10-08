package madrileno.utils.db.dsl

import cats.effect.IO
import skunk.*
import skunk.codec.all.*
import skunk.implicits.*

object AdvisoryLock {
  def classId(name: String): Int = name.hashCode

  def transactionScoped(name: String, key: Int)(using session: Session[IO]): IO[Unit] = {
    session
      .unique(sql"SELECT 1 FROM (SELECT pg_advisory_xact_lock($int4, $int4)) AS advisory_lock".query(int4))((classId(name), key))
      .void
  }
}
