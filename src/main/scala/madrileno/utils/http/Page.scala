package madrileno.utils.http

import madrileno.utils.json.JsonProtocol.*

final case class Page[A](
  items: List[A],
  total: Long,
  limit: Int,
  offset: Int)
    derives Encoder.AsObject,
      Decoder {
  def map[B](f: A => B): Page[B] = Page(items.map(f), total, limit, offset)
}
