package madrileno.utils.db.dsl

import skunk.*
import skunk.implicits.*

trait BaseRepository[T] {
  def baseFilter: Fragment[Void] = sql"True"
  val table: Table[T]
}
