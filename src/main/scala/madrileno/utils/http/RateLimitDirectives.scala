package madrileno.utils.http

import cats.effect.IO
import com.comcast.ip4s.IpAddress
import madrileno.utils.observability.TelemetryContext
import org.http4s.{Header, Request, Status}
import org.typelevel.ci.CIString
import pl.iterators.stir.server.{Directive, Directive0}

import scala.concurrent.duration.FiniteDuration

trait RateLimitDirectives { self: BaseRouter =>
  protected def rateLimiter: RateLimiter

  val byClientIp: Request[IO] => String =
    req => req.remoteAddr.fold("unknown")(_.toString)

  val byClientIpForwarded: Request[IO] => String = { req =>
    def parsedHeader(name: String): Option[IpAddress] =
      req.headers.get(CIString(name)).map(_.head.value).flatMap(_.split(",").headOption.map(_.trim)).flatMap(IpAddress.fromString)
    parsedHeader("X-Forwarded-For")
      .orElse(parsedHeader("X-Real-Ip"))
      .orElse(parsedHeader("Remote-Address"))
      .orElse(req.remoteAddr)
      .fold("unknown")(_.toString)
  }

  def byHeader(name: String): Request[IO] => String =
    req => req.headers.get(CIString(name)).map(_.head.value).getOrElse("unknown")

  def rateLimited(
    name: String,
    to: Long,
    within: FiniteDuration,
    by: Request[IO] => String = byClientIp
  )(using TelemetryContext
  ): Directive0 = Directive { inner =>
    extractRequest { req =>
      val key = s"$name:${by(req)}"
      onSuccess(rateLimiter.increment(key, within)) { count =>
        if (count > to)
          respondWithHeader(Header.Raw(CIString("Retry-After"), within.toSeconds.toString)) {
            complete(error(Status.TooManyRequests, "rate-limited", s"Too many requests; retry after ${within.toSeconds}s"))
          }
        else inner(())
      }
    }
  }
}
