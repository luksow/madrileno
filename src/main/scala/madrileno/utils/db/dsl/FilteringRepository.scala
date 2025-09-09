package madrileno.utils.db.dsl

import cats.effect.IO
import skunk.*
import skunk.codec.all.*
import skunk.implicits.*

trait SqlFilter {
  protected given conv[A]: Conversion[(SqlPredicate[A], Column[A]), AppliedFragment] = (pair: (SqlPredicate[A], Column[A])) => {
    pair._1.toAppliedFragment(pair._2.copy(codec = pair._2.codec.opt))
  }

  protected given convOpt[A]: Conversion[(SqlPredicate[A], Column[Option[A]]), AppliedFragment] = (pair: (SqlPredicate[A], Column[Option[A]])) => {
    pair._1.toAppliedFragment(pair._2)
  }

  protected trait TupleFragments[T <: Tuple] {
    def toList(t: T): List[AppliedFragment]
  }

  protected given TupleFragments[EmptyTuple] = (_: EmptyTuple) => Nil

  protected given [H, T <: Tuple](
    using
    hConv: Conversion[H, AppliedFragment],
    tFrag: TupleFragments[T]
  ): TupleFragments[H *: T] = (t: H *: T) => hConv(t.head) :: tFrag.toList(t.tail)

  protected def fromPredicatesAndSeparator[T <: Tuple](tuple: T, sep: AppliedFragment)(using tf: TupleFragments[T]): AppliedFragment = {
    tf.toList(tuple) match {
      case Nil       => sql"True" (Void)
      case h :: tail => tail.foldLeft(h)((acc, frag) => acc |+| sep |+| frag)
    }
  }

  protected val SqlAnd: AppliedFragment = sql" AND " (Void)

  def filterFragment: AppliedFragment

  protected def fromSqlOrderBy(orderBy: SqlOrderBy*): Fragment[Void] = {
    if (orderBy.isEmpty) {
      AnyOrder.fragment
    } else {
      val fragment = orderBy.map(_.fragment).reduce((acc, frag) => sql"$acc, $frag")
      sql"ORDER BY $fragment"
    }
  }

  def orderByFragment: Fragment[Void] = AnyOrder.fragment

  protected def pageLimit: Option[(Long, Long)] = None

  def offsetLimitFragment: AppliedFragment = {
    pageLimit match {
      case Some((page, limit)) => sql"OFFSET $int8 LIMIT $int8" (page * limit, limit)
      case None                => sql"" (Void)
    }
  }
}

trait SqlPredicate[A] {
  def toAppliedFragment(column: Column[Option[A]]): AppliedFragment
}

trait SqlOrderBy {
  val fragment: Fragment[Void]
}

opaque type AnyOrder <: SqlOrderBy = SqlOrderBy

val AnyOrder: AnyOrder = new SqlOrderBy {
  override val fragment: Fragment[Void] = sql""
}

trait SqlOrderByColumn extends SqlOrderBy {
  val column: Column[?]
  val ascending: Boolean

  override val fragment: Fragment[Void] = {
    if (ascending) {
      sql"${column.n} ASC"
    } else {
      sql"${column.n} DESC"
    }
  }
}

object p {
  def any[A]: SqlPredicate[A] = (_: Column[Option[A]]) => {
    sql"True" (Void)
  }

  def in[A](values: List[A]): SqlPredicate[A] = (column: Column[Option[A]]) => {
    if (values.isEmpty) {
      sql"False" (Void)
    } else {
      val mappedValues = values.map(Option(_))
      sql"(${column.n} IN (${column.c.list(mappedValues)}))" (mappedValues)
    }
  }

  def notIn[A](values: List[A]): SqlPredicate[A] = (column: Column[Option[A]]) => {
    if (values.isEmpty) {
      sql"True" (Void)
    } else {
      val mappedValues = values.map(Option(_))
      sql"(${column.n} NOT IN (${column.c.list(mappedValues)}))" (mappedValues)
    }
  }

  def like[A](value: String, caseSensitive: Boolean = true): SqlPredicate[A] = (column: Column[Option[A]]) => {
    if (caseSensitive) {
      sql"(${column.n} LIKE $text)" (value)
    } else {
      sql"(${column.n} ILIKE $text)" (value)
    }
  }

  def notLike[A](value: String, caseSensitive: Boolean = true): SqlPredicate[A] = (column: Column[Option[A]]) => {
    if (caseSensitive) {
      sql"(${column.n} NOT LIKE $text)" (value)
    } else {
      sql"(${column.n} NOT ILIKE $text)" (value)
    }
  }

  def similarTo[A](value: String): SqlPredicate[A] = (column: Column[Option[A]]) => {
    sql"(${column.n} SIMILAR TO $text)" (value)
  }

  def notSimilarTo[A](value: String): SqlPredicate[A] = (column: Column[Option[A]]) => {
    sql"(${column.n} NOT SIMILAR TO $text)" (value)
  }

  def between[A](min: A, max: A): SqlPredicate[A] = (column: Column[Option[A]]) => {
    sql"(${column.n} BETWEEN ${column.c} AND ${column.c})" (Option(min), Option(max))
  }

  def notBetween[A](min: A, max: A): SqlPredicate[A] = (column: Column[Option[A]]) => {
    sql"(${column.n} NOT BETWEEN ${column.c} AND ${column.c})" (Option(min), Option(max))
  }

  def greaterThan[A](value: A): SqlPredicate[A] = (column: Column[Option[A]]) => {
    sql"(${column.n} > ${column.c})" (Option(value))
  }

  def greaterThanOrEqual[A](value: A): SqlPredicate[A] = (column: Column[Option[A]]) => {
    sql"(${column.n} >= ${column.c})" (Option(value))
  }

  def lessThan[A](value: A): SqlPredicate[A] = (column: Column[Option[A]]) => {
    sql"(${column.n} < ${column.c})" (Option(value))
  }

  def lessThanOrEqual[A](value: A): SqlPredicate[A] = (column: Column[Option[A]]) => {
    sql"(${column.n} <= ${column.c})" (Option(value))
  }

  def isNull[A]: SqlPredicate[A] = (column: Column[Option[A]]) => {
    sql"(${column.n} IS NULL)" (Void)
  }

  def isNotNull[A]: SqlPredicate[A] = (column: Column[Option[A]]) => {
    sql"(${column.n} IS NOT NULL)" (Void)
  }

  def isTrue[A]: SqlPredicate[A] = (column: Column[Option[A]]) => {
    sql"(${column.n} IS TRUE)" (Void)
  }

  def isFalse[A]: SqlPredicate[A] = (column: Column[Option[A]]) => {
    sql"(${column.n} IS FALSE)" (Void)
  }

  def isDistinctFrom[A](value: A): SqlPredicate[A] = (column: Column[Option[A]]) => {
    sql"(${column.n} IS DISTINCT FROM ${column.c})" (Option(value))
  }

  def isNotDistinctFrom[A](value: A): SqlPredicate[A] = (column: Column[Option[A]]) => {
    sql"(${column.n} IS NOT DISTINCT FROM ${column.c})" (Option(value))
  }

  def equal[A](value: A): SqlPredicate[A] = (column: Column[Option[A]]) => {
    sql"(${column.n} = ${column.c})" (Option(value))
  }

  def notEqual[A](value: A): SqlPredicate[A] = (column: Column[Option[A]]) => {
    sql"(${column.n} != ${column.c})" (Option(value))
  }
}

trait FilteringRepository[A, F <: SqlFilter] extends BaseRepository[A] {
  def findByFilter(filter: F, lock: Lock = Lock.NoLock)(session: Session[IO]): IO[List[A]] = {
    val appliedFragment          = filter.filterFragment
    val pageLimitAppliedFragment = filter.offsetLimitFragment
    session.execute(
      sql"SELECT ${table.*} FROM ${table.n} WHERE ${appliedFragment.fragment} ${filter.orderByFragment} ${pageLimitAppliedFragment.fragment} ${lock.fragment}"
        .query(table.c)
    )(appliedFragment.argument, pageLimitAppliedFragment.argument)
  }

  def findOneByFilter(filter: F, lock: Lock = Lock.NoLock)(session: Session[IO]): IO[Option[A]] = {
    val appliedFragment = filter.filterFragment
    session.option(sql"SELECT ${table.*} FROM ${table.n} WHERE ${appliedFragment.fragment} LIMIT 1 ${lock.fragment}".query(table.c))(
      appliedFragment.argument
    )
  }

  def getByFilter(filter: F, lock: Lock = Lock.NoLock)(session: Session[IO]): IO[A] = {
    val appliedFragment = filter.filterFragment
    session.unique(sql"SELECT ${table.*} FROM ${table.n} WHERE ${appliedFragment.fragment} LIMIT 1 ${lock.fragment}".query(table.c))(
      appliedFragment.argument
    )
  }

  def existsByFilter(filter: F)(session: Session[IO]): IO[Boolean] = {
    val appliedFragment = filter.filterFragment
    session
      .option(sql"SELECT 1 FROM ${table.n} WHERE ${appliedFragment.fragment} LIMIT 1".query(int4))(appliedFragment.argument)
      .map(_.isDefined)
  }

  def countByFilter(filter: F)(session: Session[IO]): IO[Long] = {
    val appliedFragment = filter.filterFragment
    session.unique(sql"SELECT COUNT(*) FROM ${table.n} WHERE ${appliedFragment.fragment}".query(int8))(appliedFragment.argument)
  }

  def deleteByFilter(filter: F)(session: Session[IO]): IO[Unit] = {
    val appliedFragment = filter.filterFragment
    session.execute(sql"DELETE FROM ${table.n} WHERE ${appliedFragment.fragment}".command)(appliedFragment.argument).void
  }

  override val table: Table[A]
}
