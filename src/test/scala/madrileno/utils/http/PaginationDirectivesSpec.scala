package madrileno.utils.http

import cats.effect.IO
import cats.effect.unsafe.IORuntime
import org.http4s.{Method, Request, Response, Status, Uri}
import org.scalatest.funspec.AnyFunSpec
import org.scalatest.matchers.should.Matchers
import pl.iterators.stir.server.{RejectionHandler, Route, ToHttpRoutes}

class PaginationDirectivesSpec extends AnyFunSpec with Matchers {

  private given IORuntime = IORuntime.global

  private val router = new BaseRouter {}
  import router.*

  private val routes: Route =
    handleRejections(RejectionHandler.newBuilder().result().seal) {
      (get & path("cursor") & cursorPaginated[Int, Int]("after-seq", "after-id")) { req =>
        complete((req.limitValue, req.after))
      }
    }

  private def run(uri: String): Response[IO] =
    routes.toHttpRoutes.orNotFound.run(Request[IO](Method.GET, Uri.unsafeFromString(uri))).unsafeRunSync()

  private def cursorOf(uri: String): (Int, Option[(Int, Int)]) =
    run(uri).as[(Int, Option[(Int, Int)])].unsafeRunSync()

  describe("cursorPaginated") {
    it("clamps an out-of-range limit and defaults the cursor to None") {
      cursorOf("/cursor?limit=999") shouldBe ((100, None))
      cursorOf("/cursor?limit=0") shouldBe ((1, None))
      cursorOf("/cursor") shouldBe ((20, None))
    }

    it("threads a complete cursor through") {
      cursorOf("/cursor?limit=5&after-seq=10&after-id=20") shouldBe ((5, Some((10, 20))))
    }

    it("rejects a half cursor with 400") {
      run("/cursor?after-id=20").status shouldBe Status.BadRequest
      run("/cursor?after-seq=10").status shouldBe Status.BadRequest
    }
  }
}
