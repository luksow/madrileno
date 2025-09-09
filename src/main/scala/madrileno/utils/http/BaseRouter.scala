package madrileno.utils.http

import cats.effect.IO
import madrileno.utils.json.JsonProtocol
import madrileno.utils.json.JsonProtocol.*
import madrileno.utils.observability.TelemetryContext
import org.http4s.Status
import pl.iterators.kebs.enums.KebsEnum
import pl.iterators.kebs.http4sstir.matchers.KebsHttp4sStirMatchers
import pl.iterators.kebs.http4sstir.unmarshallers.{KebsHttp4sStirEnumUnmarshallers, KebsHttp4sStirUnmarshallers, KebsHttp4sStirValueEnumUnmarshallers}
import pl.iterators.stir.marshalling.ToResponseMarshallable
import pl.iterators.stir.server.Directives

import java.net.URI

trait BaseRouter
    extends JsonProtocol
    with Directives
    with KebsHttp4sStirMatchers
    with KebsHttp4sStirUnmarshallers
    with KebsHttp4sStirEnumUnmarshallers
    with KebsHttp4sStirValueEnumUnmarshallers
    with KebsEnum {
  export Status.*

  def error[E: Encoder](
    status: Status,
    typeTag: String,
    title: String,
    detail: Option[String] = None,
    extension: E = ()
  )(using tc: TelemetryContext
  ): IO[ToResponseMarshallable] = {
    tc.tracer.currentSpanContext.map { spanContext =>
      val error =
        Error(
          Some(new URI(s"result:$typeTag")),
          Some(status),
          Some(title),
          detail,
          spanContext.map(c => new URI(s"trace-id:${c.traceIdHex}")),
          extension
        )
      status -> summon[Encoder[Error[E]]](error)
    }
  }

}
