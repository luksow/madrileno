package madrileno.utils.pagination

import pl.iterators.kebs.opaque.Opaque

opaque type Limit = Int
object Limit extends Opaque[Limit, Int] {
  val Max: Int       = 100
  val Default: Limit = Limit(20)
  override def validate(value: Int): Either[String, Limit] =
    if (value >= 1 && value <= Max) Right(value) else Left(s"limit must be between 1 and $Max")
  def clamp(value: Int): Limit = Limit(value.max(1).min(Max))
}

opaque type Offset = Int
object Offset extends Opaque[Offset, Int] {
  val Zero: Offset = Offset(0)
  override def validate(value: Int): Either[String, Offset] =
    if (value >= 0) Right(value) else Left("offset must be >= 0")
  def clamp(value: Int): Offset = Offset(value.max(0))
}

enum SortDirection {
  case Asc, Desc
}

final case class PageRequest[F](
  limit: Limit,
  offset: Offset,
  sortBy: F,
  sortDir: SortDirection) {
  def limitValue: Int  = limit.unwrap
  def offsetValue: Int = offset.unwrap
}

object PageRequest {
  def firstPageBy[F](sortBy: F, sortDir: SortDirection = SortDirection.Desc): PageRequest[F] =
    PageRequest(Limit.Default, Offset.Zero, sortBy, sortDir)
}
