package madrileno.utils.http

import io.circe.Json
import madrileno.utils.json.JsonProtocol.*
import org.http4s.Status

import java.net.URI

// https://www.rfc-editor.org/rfc/rfc9457.html
case class Error[T: Encoder](
  `type`: Option[URI] = Some(URI.create("about:blank")),
  status: Option[Status] = None,
  title: Option[String] = None,
  detail: Option[String] = None,
  instance: Option[URI] = None,
  extension: T = ()) {
  protected val encoder: Encoder[T] = summon[Encoder[T]]
}

object Error {
  given [T]: Encoder[Error[T]] = new Encoder[Error[T]] {
    def apply(error: Error[T]): Json = {
      error
        .encoder(error.extension)
        .deepMerge(
          Json
            .obj(
              "type"     -> error.`type`.fold(Json.Null)(uri => Json.fromString(uri.toString)),
              "status"   -> error.status.fold(Json.Null)(status => Json.fromInt(status.code)),
              "title"    -> error.title.fold(Json.Null)(Json.fromString),
              "detail"   -> error.detail.fold(Json.Null)(Json.fromString),
              "instance" -> error.instance.fold(Json.Null)(uri => Json.fromString(uri.toString))
            )
        )
    }
  }
}
