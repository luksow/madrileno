package madrileno.utils.http

import org.http4s.{Header, Headers}
import org.typelevel.ci.*
import pl.iterators.stir.server.directives.BasicDirectives.{mapResponseHeaders, pass, provide}
import pl.iterators.stir.server.directives.PathDirectives.pathPrefix
import pl.iterators.stir.server.directives.RouteDirectives.reject
import pl.iterators.stir.server.{Directive, Directive0, Directive1}

import java.time.Instant
import java.time.format.DateTimeFormatter

// Stir directives for per-endpoint API-version branching and RFC 8594 / RFC 9745 deprecation.
// Built but unused — designed for when a real V2 lands in `ApiVersion`. See `docs/api-versioning.md`.
object ApiVersionDirectives {

  // Matches a known `ApiVersion.urlSegment` as the leading path segment and extracts the enum value.
  // Replaces `ApplicationLoader.apiPrefix: Directive0` when inner routes need to branch on the matched version.
  val apiVersionPrefix: Directive1[ApiVersion] =
    ApiVersion.values.toList
      .map(v => pathPrefix(v.urlSegment) & provide(v))
      .reduce(_ | _)

  // Gates the inner route on the request's matched `ApiVersion` equalling `target`.
  // Use under `apiVersionPrefix { matched => given ApiVersion = matched; ... }`.
  def apiVersion(target: ApiVersion)(using current: ApiVersion): Directive0 =
    if (current == target) pass else Directive(_ => reject)

  // Adds RFC 9745 `Deprecation: true` + RFC 8594 `Sunset: <http-date>` response headers.
  // Use to advertise that an endpoint or version is deprecated and will be removed by `sunset`.
  def deprecated(sunset: Instant): Directive0 = {
    val sunsetStr = DateTimeFormatter.RFC_1123_DATE_TIME.format(sunset.atOffset(java.time.ZoneOffset.UTC))
    mapResponseHeaders(_ ++ Headers(Header.Raw(ci"Deprecation", "true"), Header.Raw(ci"Sunset", sunsetStr)))
  }
}
