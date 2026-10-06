package madrileno.utils.http

import cats.effect.IO
import fs2.{Chunk, Pull}
import io.circe.parser
import madrileno.utils.observability.SensitiveJson
import org.http4s.{MediaType, Message, Request, Response}
import pl.iterators.stir.server.{Directive, Directive0, RouteResult}

import java.nio.charset.StandardCharsets.UTF_8

/** Request and response logging whose bodies are safe to ship to logs and telemetry.
  *
  * http4s-stir's `logRequest` / `logResult` can only switch body logging on or off for a whole subtree, which forces a choice between leaking
  * credentials on auth routes and losing bodies everywhere. These variants always log bodies, but never raw:
  *   - JSON bodies are logged with sensitive fields replaced by fingerprints (see [[SensitiveJson]]),
  *   - every other body is logged as its content type and size,
  *   - sensitive headers are redacted the way http4s does it.
  *
  * The streaming shape mirrors the upstream directives: the request line is logged when the route first reads the body (or without a body if it never
  * does), the response line while the response body is being written out.
  */
trait RedactedLoggingDirectives {
  import RedactedLoggingDirectives.*

  def logRedactedRequest(log: String => IO[Unit], maxBodyBytes: Int = DefaultMaxBodyBytes): Directive0 =
    Directive { inner => ctx =>
      val request = ctx.request
      if (request.isChunked || !request.contentLength.exists(_ > 0)) {
        log(describe(request, body = None)) *> inner(())(ctx)
      } else {
        IO.ref(false).flatMap { bodyLogged =>
          val observedBody = request.body.pull
            .unconsN(maxBodyBytes, allowFewer = true)
            .flatMap {
              case Some((head, tail)) =>
                Pull.output(head) >>
                  Pull.eval(bodyLogged.set(true) *> log(describe(request, Some(LoggedBody.render(request, head, maxBodyBytes))))) >>
                  tail.pull.echo
              case None =>
                Pull.eval(bodyLogged.set(true) *> log(describe(request, body = None)))
            }
            .stream
          inner(())(ctx.copy(request = request.withBodyStream(observedBody))).flatTap { _ =>
            bodyLogged.get.flatMap { logged =>
              if (logged) IO.unit else log(describe(request, Some(LoggedBody.notConsumed(request))))
            }
          }
        }
      }
    }

  def logRedactedResult(log: String => IO[Unit], maxBodyBytes: Int = DefaultMaxBodyBytes): Directive0 =
    Directive { inner => ctx =>
      inner(())(ctx).flatMap {
        case RouteResult.Complete(response) if response.isChunked =>
          log(describe(response, body = None)).as(RouteResult.Complete(response))
        case RouteResult.Complete(response) =>
          val observedBody = response.body.pull
            .unconsN(maxBodyBytes, allowFewer = true)
            .flatMap {
              case Some((head, tail)) =>
                Pull.output(head) >>
                  Pull.eval(log(describe(response, Some(LoggedBody.render(response, head, maxBodyBytes))))) >>
                  tail.pull.echo
              case None =>
                Pull.eval(log(describe(response, body = None)))
            }
            .stream
          IO.pure(RouteResult.Complete(response.withBodyStream(observedBody)))
        case rejected @ RouteResult.Rejected(rejections) =>
          log(s"Request was rejected with rejections: ${rejections.mkString(", ")}").as(rejected)
      }
    }

  private def describe(message: Message[IO], body: Option[String]): String = {
    val prelude = message match {
      case request: Request[?]   => s"${request.method} ${request.uri} ${request.httpVersion}"
      case response: Response[?] => s"${response.httpVersion} ${response.status}"
    }
    val headers  = message.headers.redactSensitive().headers.mkString("Headers(", ", ", ")")
    val bodyText = body.fold("")(text => s""" body="$text"""")
    s"$prelude $headers$bodyText"
  }
}

object RedactedLoggingDirectives {
  val DefaultMaxBodyBytes: Int = 4096

  private[http] object LoggedBody {
    def render(
      message: Message[IO],
      head: Chunk[Byte],
      maxBodyBytes: Int
    ): String = {
      val declaredLength = message.contentLength
      val size           = declaredLength.fold(s"${head.size}+")(_.toString)
      // Without a declared length we cannot tell a body of exactly maxBodyBytes from a longer one, so err on the safe side.
      val truncated = declaredLength.fold(head.size >= maxBodyBytes)(_ > maxBodyBytes)
      if (!isJson(message)) s"<${mediaTypeOf(message)}, $size bytes>"
      else if (truncated) s"<json, $size bytes, over the $maxBodyBytes-byte log limit>"
      else {
        parser
          .parse(new String(head.toArray, UTF_8))
          .fold(_ => s"<malformed json, $size bytes>", json => SensitiveJson.redact(json).noSpaces)
      }
    }

    def notConsumed(request: Request[IO]): String =
      s"<not consumed, ${request.contentLength.fold("?")(_.toString)} bytes>"

    private def isJson(message: Message[IO]): Boolean =
      message.contentType.exists { contentType =>
        contentType.mediaType == MediaType.application.json || contentType.mediaType.subType.endsWith("+json")
      }

    private def mediaTypeOf(message: Message[IO]): String =
      message.contentType.fold("unknown content type")(contentType => s"${contentType.mediaType.mainType}/${contentType.mediaType.subType}")
  }
}
