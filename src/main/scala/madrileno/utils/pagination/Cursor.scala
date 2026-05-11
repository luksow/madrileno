package madrileno.utils.pagination

final case class Cursor[A](items: List[A], hasMore: Boolean) {
  def map[B](f: A => B): Cursor[B] = Cursor(items.map(f), hasMore)
}
