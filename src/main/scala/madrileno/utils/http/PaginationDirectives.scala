package madrileno.utils.http

import madrileno.utils.pagination.{Limit, Offset, PageRequest, SortDirection}
import pl.iterators.stir.server.Directive1
import pl.iterators.stir.unmarshalling.Unmarshaller

trait PaginationDirectives { self: BaseRouter =>
  def paginated[F](defaultSort: F, defaultSortDir: SortDirection = SortDirection.Desc)(using Unmarshaller[String, F]): Directive1[PageRequest[F]] =
    parameters(
      "sort-by".as[F].withDefault(defaultSort),
      "sort-dir".as[SortDirection].withDefault(defaultSortDir),
      "limit".as[Int].withDefault(Limit.Default.unwrap),
      "offset".as[Int].withDefault(Offset.Zero.unwrap)
    ).tmap { case (sortBy, sortDir, limit, offset) =>
      Tuple1(PageRequest(Limit(limit.max(1).min(Limit.Max)), Offset(offset.max(0)), sortBy, sortDir))
    }
}
