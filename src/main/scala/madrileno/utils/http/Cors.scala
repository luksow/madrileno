package madrileno.utils.http

import cats.effect.IO
import cats.syntax.all.*
import madrileno.utils.observability.LoggingSupport
import org.http4s.Uri
import org.http4s.headers.Origin
import org.http4s.server.middleware.{CORS, CORSPolicy}
import pureconfig.ConfigReader

import java.net.URI
import scala.concurrent.duration.FiniteDuration

final case class CorsConfig(
  enabled: Boolean,
  allowedOrigins: String,
  maxAge: FiniteDuration)
    derives ConfigReader

object Cors extends LoggingSupport {

  /** `allowed-origins` resolution: an explicit comma-separated list ⇒ exactly those origins; `*` ⇒ any origin; empty ⇒ any origin in dev, otherwise
    * the host of `base-url` (a safe fallback that's zero-config for same-origin SPA+API deployments and never silently `*` in prod). Methods and
    * request headers are allowed wildcard. No `Access-Control-Allow-Credentials` — auth here is a client-set Bearer token, not cookies.
    */
  def policy(
    config: CorsConfig,
    environment: String,
    baseUrl: URI
  ): IO[CORSPolicy] = {
    val base       = CORS.policy.withAllowMethodsAll.withAllowHeadersAll.withMaxAge(config.maxAge)
    val configured = config.allowedOrigins.split(',').iterator.map(_.trim).filter(_.nonEmpty).toList
    if (configured.contains("*")) IO.pure(base.withAllowOriginAll)
    else if (configured.nonEmpty) parseHosts(configured).map(base.withAllowOriginHost)
    else if (environment == "dev") IO.pure(base.withAllowOriginAll)
    else parseHosts(List(baseUrl.toString)).map(base.withAllowOriginHost)
  }

  private def parseHosts(values: List[String]): IO[Set[Origin.Host]] =
    values
      .flatTraverse { value =>
        originHost(value) match {
          case Some(host) => IO.pure(List(host))
          case None =>
            loggerWithoutTracing
              .warn(s"CORS: ignoring origin '$value' — expected a scheme and host like https://app.example.com")
              .as(List.empty[Origin.Host])
        }
      }
      .map(_.toSet)

  private def originHost(value: String): Option[Origin.Host] =
    Uri.fromString(value).toOption.flatMap { uri =>
      (uri.scheme, uri.host).mapN((scheme, host) => Origin.Host(scheme, host, uri.port))
    }
}
