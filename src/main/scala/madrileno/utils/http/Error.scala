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

  private def decodeURI(s: String, field: String): Decoder.Result[URI] =
    try Right(URI.create(s))
    catch { case e: IllegalArgumentException => Left(io.circe.DecodingFailure(s"Invalid URI in '$field': ${e.getMessage}", Nil)) }

  private def decodeStatus(code: Int): Decoder.Result[Status] =
    Status.fromInt(code).left.map(e => io.circe.DecodingFailure(s"Invalid status code: ${e.message}", Nil))

  private def traverseOpt[A, B](opt: Option[A])(f: A => Decoder.Result[B]): Decoder.Result[Option[B]] =
    opt match {
      case Some(a) => f(a).map(Some(_))
      case None    => Right(None)
    }

  given Decoder[Error[Unit]] = new Decoder[Error[Unit]] {
    def apply(c: io.circe.HCursor): Decoder.Result[Error[Unit]] = {
      for {
        tpe      <- c.downField("type").as[Option[String]]
        tpeUri   <- traverseOpt(tpe)(decodeURI(_, "type"))
        status   <- c.downField("status").as[Option[Int]]
        statusV  <- traverseOpt(status)(decodeStatus)
        title    <- c.downField("title").as[Option[String]]
        detail   <- c.downField("detail").as[Option[String]]
        instance <- c.downField("instance").as[Option[String]]
        instUri  <- traverseOpt(instance)(decodeURI(_, "instance"))
      } yield Error[Unit](`type` = tpeUri, status = statusV, title = title, detail = detail, instance = instUri)
    }
  }
}
