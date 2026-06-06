package madrileno.utils.http

import cats.effect.kernel.Unique
import org.http4s.{Header, Headers}
import org.typelevel.ci.*
import org.typelevel.vault.Key
import pl.iterators.stir.server.directives.BasicDirectives.{extractRequest, mapRequest, mapResponseHeaders, pass, provide}
import pl.iterators.stir.server.directives.PathDirectives.pathPrefix
import pl.iterators.stir.server.directives.RouteDirectives.reject
import pl.iterators.stir.server.{Directive, Directive0, Directive1}

import java.time.Instant
import java.time.format.DateTimeFormatter

// Stir directives for per-endpoint API-version branching and RFC 8594 / RFC 9745 deprecation.
// Built but unused — designed for when a real V2 lands in `ApiVersion`. See `docs/api-versioning.md`.
object ApiVersionDirectives {

  private val attrKey: Key[ApiVersion] = new Key[ApiVersion](new Unique.Token)

  // Matches a known `ApiVersion.urlSegment` as the leading path segment and stores the matched
  // version on the request attributes. Drop-in replacement for `ApplicationLoader.apiPrefix`
  // when inner routes need version awareness; same `Directive0` shape, no callback parameter.
  val apiVersionPrefix: Directive0 =
    ApiVersion.values.toList
      .map(v => pathPrefix(v.urlSegment) & mapRequest(_.withAttribute(attrKey, v)))
      .reduce(_ | _)

  // Extracts the matched `ApiVersion` from request attributes. Rejects when called outside an
  // `apiVersionPrefix` scope (programmer error).
  val currentApiVersion: Directive1[ApiVersion] =
    extractRequest.flatMap { req =>
      req.attributes.lookup(attrKey).fold(Directive(_ => reject): Directive1[ApiVersion])(provide)
    }

  // Gates the inner route on the matched version equalling `target`. Looks up the version
  // from request attributes; no caller-side threading.
  def apiVersion(target: ApiVersion): Directive0 =
    currentApiVersion.flatMap { current =>
      if (current == target) pass else Directive(_ => reject)
    }

  // Adds RFC 9745 `Deprecation: true` + RFC 8594 `Sunset: <http-date>` response headers.
  def deprecated(sunset: Instant): Directive0 = {
    val sunsetStr = DateTimeFormatter.RFC_1123_DATE_TIME.format(sunset.atOffset(java.time.ZoneOffset.UTC))
    mapResponseHeaders(_ ++ Headers(Header.Raw(ci"Deprecation", "true"), Header.Raw(ci"Sunset", sunsetStr)))
  }
}
