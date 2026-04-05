package madrileno.support

import cats.effect.IO
import cats.effect.unsafe.IORuntime
import org.http4s.{HttpRoutes, Request}
import org.scalatest.funspec.AnyFunSpec
import org.scalatest.matchers.should.Matchers
import pl.iterators.baklava.http4s.BaklavaHttp4s
import pl.iterators.baklava.scalatest.{BaklavaScalatest, ScalatestAsExecution}
import pl.iterators.baklava.{HttpBearer, SecurityScheme}
import pl.iterators.kebs.baklava.params.KebsBaklavaParams
import pl.iterators.kebs.baklava.params.enums.{KebsBaklavaEnumsParams, KebsBaklavaValueEnumsParams}
import pl.iterators.kebs.baklava.schema.KebsBaklavaSchema
import pl.iterators.kebs.baklava.schema.enums.{KebsBaklavaEnumsSchema, KebsBaklavaValueEnumsSchema}
import pl.iterators.stir.server.{Route, ToHttpRoutes}

trait BaseRouteSpec
    extends AnyFunSpec
    with Matchers
    with BaklavaHttp4s[Unit, Unit, ScalatestAsExecution]
    with BaklavaScalatest[HttpRoutes[IO], BaklavaHttp4s.ToEntityMarshaller, BaklavaHttp4s.FromEntityUnmarshaller]
    with KebsBaklavaParams
    with KebsBaklavaSchema
    with KebsBaklavaEnumsSchema
    with KebsBaklavaEnumsParams
    with KebsBaklavaValueEnumsParams
    with KebsBaklavaValueEnumsSchema {

  def route: Route

  lazy val allRoutes: HttpRoutes[IO] = route.toHttpRoutes

  override implicit val runtime: IORuntime = IORuntime.global

  override def strictHeaderCheckDefault: Boolean = false

  override def performRequest(routes: HttpRoutes[IO], request: Request[IO]): HttpResponse =
    routes.orNotFound.run(request).unsafeRunSync()

  // Shared auth helpers for route specs
  protected val bearer: HttpBearer           = HttpBearer(bearerFormat = "JWT")
  protected val bearerScheme: SecurityScheme = SecurityScheme("bearer", bearer)
}
