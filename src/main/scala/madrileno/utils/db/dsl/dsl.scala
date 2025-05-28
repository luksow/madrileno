package madrileno.utils.db.dsl

import skunk.*
import skunk.implicits.*
import skunk.codec.all.*
import org.typelevel.twiddles.Iso
import pl.iterators.kebs.core.enums.EnumLike
import pl.iterators.kebs.core.macros.ValueClassLike
import pl.iterators.kebs.enums.KebsEnum

import java.time.{Instant, OffsetDateTime, ZoneOffset}

case class Column[A](
  name: String,
  tableName: String,
  codec: Codec[A]) {
  def n: Fragment[Void]                  = sql"#${name}"
  def n(tableAs: String): Fragment[Void] = sql"#${tableAs}.#${name}"
  def c: Codec[A]                        = codec
}

abstract class Table[T](tableName: String) extends MappingHelpers[T] with CodecHelpers {
  def column[A](name: String, codec: Codec[A]): Column[A] =
    Column(name, tableName, codec)

  def mapping: (List[Column[?]], Codec[T])

  def columns: List[Column[?]] = mapping._1

  def columnsToFragment(columns: List[Column[?]]): Fragment[Void] = {
    columns match {
      case Nil => sql""
      case head :: tail =>
        tail.foldLeft(head.n) { (acc, c) =>
          sql"$acc, ${c.n}"
        }
    }
  }
  def columnsToFragment(columns: List[Column[?]], as: String): Fragment[Void] = {
    columns match {
      case Nil => sql""
      case head :: tail =>
        tail.foldLeft(head.n(as)) { (acc, c) =>
          sql"$acc, ${c.n(as)}"
        }
    }
  }

  def * : Fragment[Void] = columnsToFragment(mapping._1)

  def *(as: String): Fragment[Void] = columnsToFragment(mapping._1, as)

  def c: Codec[T] = mapping._2

  def n: Fragment[Void] = sql"#${tableName}"
}

trait CodecHelpers extends KebsEnum {
  extension [A](codec: Codec[A]) {
    def as[T](using valueClassLike: ValueClassLike[T, A]): Codec[T] = codec.imap(valueClassLike.apply)(valueClassLike.unapply)
  }

  extension (codec: Codec[String]) {
    def asEnum[T](using enumLike: EnumLike[T]): Codec[T] = codec.imap(enumLike.valueOf)(_.toString)
  }

  extension (codec: Codec[OffsetDateTime]) {
    def asInstant[T]: Codec[Instant] = codec.imap(_.toInstant)(_.atOffset(ZoneOffset.UTC))
  }
//  def enumOf[T <: scala.reflect.Enum](es: Array[T], typeName: String) =
//    `enum`[T](_.toString(), s => es.find(_.toString == s), Type(typeName))
//
//  def arrOf[A](codec: Codec[A]): Codec[Arr[A]] = {
//    val ty     = Type(s"_${codec.types.head.name}", codec.types)
//    val encode = (elem: A) => codec.encode(elem).head.get
//    val decode = (str: String) => codec.decode(0, List(Some(str))).left.map(_.message)
//    Codec.array[A](encode.andThen(_.value), decode, ty)
//  }
//
//  def listOf[A](codec: Codec[A]): Codec[List[A]] =
//    arrOf(codec).imap(_.toList)(l => Arr(l*))
}

trait MappingHelpers[BaseType] {
  trait GetColumnFromElement[A] {
    def column(a: A): Column[?]
  }

  given [A]: GetColumnFromElement[Column[A]] = (a: Column[A]) => a

  trait GetColumnsFromTuple[A] {
    def columns(a: A): List[Column[?]]
  }

  given [EmptyTuple]: GetColumnsFromTuple[EmptyTuple] = (_: EmptyTuple) => Nil

  given [H, T <: Tuple](using GetColumnsFromTuple[T], GetColumnFromElement[H]): GetColumnsFromTuple[H *: T] = (tuple: H *: T) => {
    summon[GetColumnFromElement[H]]
      .column(tuple.head) +: summon[GetColumnsFromTuple[T]]
      .columns(tuple.tail)
  }

  trait GetCodecFromElement[A, B] {
    def codec(a: A): Codec[B]
  }
  given [B]: GetCodecFromElement[Column[B], B] = (a: Column[B]) => a.c

  trait GetCodecsFromTuple[A <: Tuple, B <: Tuple] {
    def codecs(a: A): Codec[B]
  }

  given [H, IH, T <: Tuple, IT <: Tuple](using GetCodecFromElement[H, IH], GetCodecsFromTuple[T, IT]): GetCodecsFromTuple[H *: T, IH *: IT] =
    (tuple: H *: T) => {
      summon[GetCodecFromElement[H, IH]]
        .codec(tuple.head) *: summon[GetCodecsFromTuple[T, IT]]
        .codecs(tuple.tail)
    }

  given [H, IH](using GetCodecFromElement[H, IH]): GetCodecsFromTuple[H *: EmptyTuple, IH *: EmptyTuple] =
    (tuple: H *: EmptyTuple) =>
      summon[GetCodecFromElement[H, IH]]
        .codec(tuple.head)
        .imap((a: IH) => a *: EmptyTuple)(t => t.head)

  private def fromTuple[A <: Tuple, C <: Tuple](
    t: A
  )(using
    GetColumnsFromTuple[A],
    GetCodecsFromTuple[A, C],
    Iso[C, BaseType]
  ): (List[Column[?]], Codec[BaseType]) = {
    val columns = summon[GetColumnsFromTuple[A]].columns(t)
    val codec   = summon[GetCodecsFromTuple[A, C]].codecs(t).to[BaseType]
    (columns, codec)
  }

  given [A <: Tuple, C <: Tuple](
    using GetColumnsFromTuple[A],
    GetCodecsFromTuple[A, C],
    Iso[C, BaseType]
  ): Conversion[A, (List[Column[?]], Codec[BaseType])] = (t: A) => fromTuple(t)
}
