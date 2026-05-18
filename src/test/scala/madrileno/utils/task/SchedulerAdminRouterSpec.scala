package madrileno.utils.task

import cats.effect.IO
import madrileno.support.{BaseRouteSpec, TestApplicationLoader}
import org.http4s.headers.Authorization
import org.http4s.{BasicCredentials, Headers, Method, Request, Status, Uri}
import pl.iterators.stir.server.Route

class SchedulerAdminRouterSpec extends BaseRouteSpec with TestApplicationLoader {
  override def route: Route = application.routes(wsb)

  private val jobsUri = Uri.unsafeFromString("/admin/jobs")

  describe("/admin/jobs") {
    it("returns the jobs page with the registered recurring task when authenticated") {
      val request  = Request[IO](method = Method.GET, uri = jobsUri, headers = Headers(Authorization(BasicCredentials("admin", "admin"))))
      val response = allRoutes.orNotFound.run(request).unsafeRunSync()
      response.status shouldBe Status.Ok
      response.contentType.exists(_.mediaType.subType == "html") shouldBe true
      val body = response.bodyText.compile.string.unsafeRunSync()
      // scripts:auction-block-start
      body should include("close-expired-auctions")
      // scripts:auction-block-end
      body should include("Recurring tasks")
      body should include("Registered task types")
      body should include("send-mail")
    }

    it("rejects requests without credentials with 401") {
      val request  = Request[IO](method = Method.GET, uri = jobsUri)
      val response = allRoutes.orNotFound.run(request).unsafeRunSync()
      response.status shouldBe Status.Unauthorized
    }

    it("rejects requests with wrong credentials with 401") {
      val request  = Request[IO](method = Method.GET, uri = jobsUri, headers = Headers(Authorization(BasicCredentials("admin", "nope"))))
      val response = allRoutes.orNotFound.run(request).unsafeRunSync()
      response.status shouldBe Status.Unauthorized
    }
  }
}
