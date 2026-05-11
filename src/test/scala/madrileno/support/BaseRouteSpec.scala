package madrileno.support

import cats.effect.IO
import cats.effect.unsafe.IORuntime
import io.circe.Decoder
import madrileno.utils.http.Error
import madrileno.utils.pagination.Page
import org.http4s.server.websocket.WebSocketBuilder2
import org.http4s.{HttpRoutes, Request}
import org.scalatest.funspec.AnyFunSpec
import org.scalatest.matchers.should.Matchers
import pl.iterators.baklava.http4s.BaklavaHttp4s
import pl.iterators.baklava.scalatest.{BaklavaScalatest, ScalatestAsExecution}
import pl.iterators.baklava.{HttpBearer, Schema, SchemaType, SecurityScheme}
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

  protected val wsb: WebSocketBuilder2[IO] = WebSocketBuilder2[IO].unsafeRunSync()

  override def strictHeaderCheckDefault: Boolean = false

  override def performRequest(routes: HttpRoutes[IO], request: Request[IO]): HttpResponse =
    routes.orNotFound.run(request).unsafeRunSync()

  protected val bearer: HttpBearer           = HttpBearer(bearerFormat = "JWT")
  protected val bearerScheme: SecurityScheme = SecurityScheme("bearer", bearer)

  given [A: Decoder]: Decoder[Page[A]] = Decoder.derived

  // RFC 9457 error schema
  given Schema[Error[Unit]] = new Schema[Error[Unit]] {
    val className = "Error"
    val `type`    = SchemaType.ObjectType
    val format    = None
    val properties: Map[String, Schema[?]] = Map(
      "type"     -> Schema.stringSchema.withDescription("A URI reference identifying the problem type"),
      "status"   -> Schema.intSchema.withDescription("HTTP status code"),
      "title"    -> Schema.stringSchema.withDescription("Short human-readable summary"),
      "detail"   -> Schema.optionSchema(using Schema.stringSchema).withDescription("Human-readable explanation"),
      "instance" -> Schema.optionSchema(using Schema.stringSchema).withDescription("URI reference identifying the specific occurrence")
    )
    val items                       = None
    val `enum`                      = None
    val required                    = true
    val additionalProperties        = false
    val default                     = None
    val description: Option[String] = Some("RFC 9457 Problem Details error response")
  }
}
