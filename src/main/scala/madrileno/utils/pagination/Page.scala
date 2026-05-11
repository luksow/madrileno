package madrileno.utils.pagination

final case class Page[A](
  items: List[A],
  total: Long,
  limit: Int,
  offset: Int) {
  def map[B](f: A => B): Page[B] = Page(items.map(f), total, limit, offset)
}
