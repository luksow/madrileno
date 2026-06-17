package madrileno.utils.http

import madrileno.utils.pagination.{CursorRequest, Limit, Offset, PageRequest, SortDirection}
import pl.iterators.stir.server.Directive1
import pl.iterators.stir.unmarshalling.Unmarshaller

trait PaginationDirectives { self: BaseRouter =>
  private def limit: Directive1[Limit] =
    parameters("limit".as[Int].?).map(Limit.orDefault)

  def paginated[F](defaultSort: F, defaultSortDir: SortDirection = SortDirection.Desc)(using Unmarshaller[String, F]): Directive1[PageRequest[F]] =
    (limit & parameters("sort-by".as[F].withDefault(defaultSort), "sort-dir".as[SortDirection].withDefault(defaultSortDir), "offset".as[Int].?))
      .tmap { case (lim, sortBy, sortDir, offset) =>
        Tuple1(PageRequest(lim, Offset.orDefault(offset), sortBy, sortDir))
      }

  def cursorPaginated[S, I](afterSortParam: String, afterIdParam: String)(using Unmarshaller[String, S], Unmarshaller[String, I])
    : Directive1[CursorRequest[(S, I)]] =
    (limit & parameters(afterSortParam.as[S].?, afterIdParam.as[I].?)).tflatMap { case (lim, sortAfter, idAfter) =>
      validate(sortAfter.isDefined == idAfter.isDefined, s"'$afterSortParam' and '$afterIdParam' must be supplied together").tmap { _ =>
        Tuple1(CursorRequest(lim, sortAfter.zip(idAfter)))
      }
    }

  def cursorPaginatedByKey[I](afterParam: String)(using Unmarshaller[String, I]): Directive1[CursorRequest[I]] =
    (limit & parameters(afterParam.as[I].?)).tmap { case (lim, after) =>
      Tuple1(CursorRequest(lim, after))
    }
}
