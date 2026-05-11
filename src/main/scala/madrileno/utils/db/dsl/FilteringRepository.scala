package madrileno.utils.db.dsl

import cats.effect.IO
import skunk.*
import skunk.codec.all.*
import skunk.implicits.*

import scala.compiletime.*
import scala.deriving.Mirror

private val TrueFragment: AppliedFragment = sql"1=1" (Void)
private val AndFragment: AppliedFragment  = sql" AND " (Void)

def orderByColumns(columns: (Column[?], Boolean)*): Fragment[Void] = {
  if (columns.isEmpty) {
    AnyOrder.fragment
  } else {
    val parts = columns.map { case (col, ascending) => if (ascending) sql"${col.n} ASC" else sql"${col.n} DESC" }.reduce((a, b) => sql"$a, $b")
    sql"ORDER BY $parts"
  }
}

def offsetLimitClause(offset: Long, limit: Long): AppliedFragment = sql"OFFSET $int8 LIMIT $int8" (offset, limit)

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

  protected def fromPredicates[T <: Tuple](tuple: T)(using tf: TupleFragments[T]): AppliedFragment = {
    tf.toList(tuple) match {
      case Nil       => sql"1=1" (Void)
      case h :: tail => tail.foldLeft(h)((acc, frag) => acc |+| AndFragment |+| frag)
    }
  }

  def filterFragment: AppliedFragment

  def orderByFragment: Fragment[Void] = AnyOrder.fragment

  protected def offsetLimit: Option[(Long, Long)] = None

  def offsetLimitFragment: AppliedFragment = {
    offsetLimit match {
      case Some((offset, limit)) => offsetLimitClause(offset, limit)
      case None                  => sql"" (Void)
    }
  }
}

final case class PageWindow(
  offset: Long,
  limit: Long,
  sortColumn: Column[?],
  ascending: Boolean)

trait PageableSqlFilter extends SqlFilter {
  protected def pageWindow: Option[PageWindow] = None
  protected def tieBreakColumn: Column[?]

  override def orderByFragment: Fragment[Void] =
    pageWindow.fold(super.orderByFragment)(w => orderByColumns(w.sortColumn -> w.ascending, tieBreakColumn -> w.ascending))

  override protected def offsetLimit: Option[(Long, Long)] = pageWindow.map(w => (w.offset, w.limit))
}

object SqlFilterDerivation {
  trait ColumnPairing[Pred, Col] {
    def toFragment(pred: Pred, col: Col): AppliedFragment
  }

  given pairWithColumn[A]: ColumnPairing[SqlPredicate[A], Column[A]] = (pred, col) => pred.toAppliedFragment(col.copy(codec = col.codec.opt))

  given pairWithOptColumn[A]: ColumnPairing[SqlPredicate[A], Column[Option[A]]] = (pred, col) => pred.toAppliedFragment(col)

  inline def buildFragments[Preds <: Tuple, Cols <: Tuple](preds: Preds, cols: Cols): List[AppliedFragment] =
    inline erasedValue[(Preds, Cols)] match {
      case _: (EmptyTuple, EmptyTuple) => Nil
      case _: (ph *: pt, ch *: ct) =>
        val pairing = summonInline[ColumnPairing[ph, ch]]
        val p       = preds.asInstanceOf[ph *: pt] // scalafix:ok DisableSyntax.asInstanceOf
        val c       = cols.asInstanceOf[ch *: ct] // scalafix:ok DisableSyntax.asInstanceOf
        pairing.toFragment(p.head, c.head) :: buildFragments[pt, ct](p.tail, c.tail)
    }

  inline def filterFragment[F <: Product, Cols <: Tuple](filter: F, columns: Cols)(using m: Mirror.ProductOf[F]): AppliedFragment = {
    val fragments = buildFragments[m.MirroredElemTypes, Cols](Tuple.fromProductTyped(filter), columns)
    fragments match {
      case Nil       => TrueFragment
      case h :: tail => tail.foldLeft(h)((acc, frag) => acc |+| AndFragment |+| frag)
    }
  }
}

trait SqlPredicate[A] {
  def toAppliedFragment(column: Column[Option[A]]): AppliedFragment
}

object AnyOrder {
  val fragment: Fragment[Void] = sql""
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

  def like(value: String, caseSensitive: Boolean = true): SqlPredicate[String] = (column: Column[Option[String]]) => {
    if (caseSensitive) {
      sql"(${column.n} LIKE $text)" (value)
    } else {
      sql"(${column.n} ILIKE $text)" (value)
    }
  }

  def notLike(value: String, caseSensitive: Boolean = true): SqlPredicate[String] = (column: Column[Option[String]]) => {
    if (caseSensitive) {
      sql"(${column.n} NOT LIKE $text)" (value)
    } else {
      sql"(${column.n} NOT ILIKE $text)" (value)
    }
  }

  def similarTo(value: String): SqlPredicate[String] = (column: Column[Option[String]]) => {
    sql"(${column.n} SIMILAR TO $text)" (value)
  }

  def notSimilarTo(value: String): SqlPredicate[String] = (column: Column[Option[String]]) => {
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
  def findByFilter(filter: F, lock: Lock = Lock.NoLock)(using session: Session[IO]): IO[List[A]] = {
    val appliedFragment            = filter.filterFragment
    val offsetLimitAppliedFragment = filter.offsetLimitFragment
    session.execute(
      sql"SELECT ${table.*} FROM ${table.n} WHERE ${appliedFragment.fragment} ${filter.orderByFragment} ${offsetLimitAppliedFragment.fragment} ${lock.fragment}"
        .query(table.c)
    )(appliedFragment.argument, offsetLimitAppliedFragment.argument)
  }

  def findPageByFilter(filter: F, lock: Lock = Lock.NoLock)(using session: Session[IO]): IO[(List[A], Long)] =
    for {
      rows  <- findByFilter(filter, lock)
      total <- countByFilter(filter)
    } yield (rows, total)

  def findOneByFilter(filter: F, lock: Lock = Lock.NoLock)(using session: Session[IO]): IO[Option[A]] = {
    val appliedFragment = filter.filterFragment
    session.option(
      sql"SELECT ${table.*} FROM ${table.n} WHERE ${appliedFragment.fragment} ${filter.orderByFragment} LIMIT 1 ${lock.fragment}".query(table.c)
    )(appliedFragment.argument)
  }

  def getByFilter(filter: F, lock: Lock = Lock.NoLock)(using session: Session[IO]): IO[A] = {
    val appliedFragment = filter.filterFragment
    session.unique(
      sql"SELECT ${table.*} FROM ${table.n} WHERE ${appliedFragment.fragment} ${filter.orderByFragment} LIMIT 1 ${lock.fragment}".query(table.c)
    )(appliedFragment.argument)
  }

  def existsByFilter(filter: F)(using session: Session[IO]): IO[Boolean] = {
    val appliedFragment = filter.filterFragment
    session
      .option(sql"SELECT 1 FROM ${table.n} WHERE ${appliedFragment.fragment} LIMIT 1".query(int4))(appliedFragment.argument)
      .map(_.isDefined)
  }

  def countByFilter(filter: F)(using session: Session[IO]): IO[Long] = {
    val appliedFragment = filter.filterFragment
    session.unique(sql"SELECT COUNT(*) FROM ${table.n} WHERE ${appliedFragment.fragment}".query(int8))(appliedFragment.argument)
  }

  def deleteByFilter(filter: F)(using session: Session[IO]): IO[Unit] = {
    val appliedFragment = filter.filterFragment
    session.execute(sql"DELETE FROM ${table.n} WHERE ${appliedFragment.fragment}".command)(appliedFragment.argument).void
  }

  override val table: Table[A]
}
