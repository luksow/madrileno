package madrileno.utils.http

import cats.effect.IO
import com.comcast.ip4s.{Cidr, IpAddress}
import madrileno.utils.observability.TelemetryContext
import org.http4s.{Header, Request, Status}
import org.typelevel.ci.CIString
import pl.iterators.stir.server.{Directive, Directive0}

import scala.concurrent.duration.FiniteDuration

trait RateLimitDirectives { self: BaseRouter =>
  protected def rateLimiterRuntime: RateLimiterRuntime

  private def rateLimiter: RateLimiter              = rateLimiterRuntime.rateLimiter
  private def trustedProxies: List[Cidr[IpAddress]] = rateLimiterRuntime.trustedProxies

  val byClientIp: Request[IO] => String =
    req => req.remoteAddr.fold("unknown")(_.toString)

  val byClientIpForwarded: Request[IO] => String = { req =>
    def isTrusted(ip: IpAddress): Boolean = trustedProxies.exists(_.contains(ip))
    req.remoteAddr match {
      case Some(socket) if isTrusted(socket) =>
        forwardedFor(req).reverse.dropWhile(isTrusted).headOption.getOrElse(socket).toString
      case Some(socket) => socket.toString
      case None         => "unknown"
    }
  }

  private def forwardedFor(req: Request[IO]): List[IpAddress] =
    req.headers
      .get(CIString("X-Forwarded-For"))
      .toList
      .flatMap(_.toList)
      .flatMap(_.value.split(",").toList)
      .map(_.trim)
      .flatMap(IpAddress.fromString)

  def byHeader(name: String): Request[IO] => String =
    req => req.headers.get(CIString(name)).map(_.head.value).getOrElse("unknown")

  def rateLimited(
    name: String,
    to: Long,
    within: FiniteDuration,
    by: Request[IO] => String = byClientIpForwarded
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
