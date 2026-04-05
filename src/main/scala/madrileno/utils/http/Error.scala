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

  given Decoder[Error[Unit]] = new Decoder[Error[Unit]] {
    def apply(c: io.circe.HCursor): Decoder.Result[Error[Unit]] = {
      for {
        tpe      <- c.downField("type").as[Option[String]]
        status   <- c.downField("status").as[Option[Int]]
        title    <- c.downField("title").as[Option[String]]
        detail   <- c.downField("detail").as[Option[String]]
        instance <- c.downField("instance").as[Option[String]]
      } yield Error[Unit](
        `type` = tpe.map(URI.create),
        status = status.map(Status.fromInt(_).toOption).flatten,
        title = title,
        detail = detail,
        instance = instance.map(URI.create)
      )
    }
  }
}
