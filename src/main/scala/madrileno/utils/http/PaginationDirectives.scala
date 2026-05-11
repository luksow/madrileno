package madrileno.utils.http

import madrileno.utils.pagination.{CursorRequest, Limit, Offset, PageRequest, SortDirection}
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
      Tuple1(PageRequest(Limit.clamp(limit), Offset.clamp(offset), sortBy, sortDir))
    }

  def cursorPaginated[S, I](afterSortParam: String, afterIdParam: String)(using Unmarshaller[String, S], Unmarshaller[String, I])
    : Directive1[CursorRequest[(S, I)]] =
    parameters("limit".as[Int].withDefault(Limit.Default.unwrap), afterSortParam.as[S].?, afterIdParam.as[I].?).tflatMap {
      case (limit, sortAfter, idAfter) =>
        validate(sortAfter.isDefined == idAfter.isDefined, s"'$afterSortParam' and '$afterIdParam' must be supplied together").tmap { _ =>
          Tuple1(CursorRequest(Limit.clamp(limit), sortAfter.zip(idAfter)))
        }
    }
}
