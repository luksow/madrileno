package madrileno.utils.http

import cats.effect.IO
import madrileno.auth.domain.AuthContext
import madrileno.utils.observability.TelemetryContext
import org.http4s.{Header, Request, Status}
import org.typelevel.ci.CIString
import pl.iterators.stir.server.Directive0

trait RateLimitDirectives { self: BaseRouter =>
  protected def rateLimiter: RateLimiter

  val byClientIp: Request[IO] => String = { req =>
    def header(name: String): Option[String] = req.headers.get(CIString(name)).map(_.head.value)
    val xff: Option[String]                  = header("X-Forwarded-For").flatMap(_.split(",").headOption.map(_.trim).filter(_.nonEmpty))
    xff
      .orElse(header("X-Real-Ip"))
      .orElse(header("Remote-Address"))
      .orElse(req.remoteAddr.map(_.toString))
      .getOrElse("unknown")
  }

  def byAuthedUser(auth: AuthContext): Request[IO] => String =
    _ => s"user:${auth.userId}"

  def byHeader(name: String): Request[IO] => String =
    req => req.headers.get(CIString(name)).map(_.head.value).getOrElse("unknown")

  def rateLimited(
    name: String,
    limit: RateLimit,
    by: Request[IO] => String = byClientIp
  )(using TelemetryContext
  ): Directive0 = {
    extractRequest.flatMap { req =>
      val key = s"$name:${by(req)}"
      onSuccess(rateLimiter.increment(key, limit.within)).flatMap { count =>
        if (count > limit.to)
          respondWithHeader(Header.Raw(CIString("Retry-After"), limit.within.toSeconds.toString)) &
            complete(error(Status.TooManyRequests, "rate-limited", s"Too many requests; retry after ${limit.within.toSeconds}s")).toDirective[Unit]
        else pass
      }
    }
  }
}
