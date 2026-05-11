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
  def policy(
    config: CorsConfig,
    environment: String,
    baseUrl: URI
  ): IO[Option[CORSPolicy]] = {
    if (!config.enabled) IO.pure(None)
    else {
      val base       = CORS.policy.withAllowMethodsAll.withAllowHeadersAll.withMaxAge(config.maxAge)
      val configured = config.allowedOrigins.split(',').iterator.map(_.trim).filter(_.nonEmpty).toList
      val built =
        if (configured.contains("*")) IO.pure(base.withAllowOriginAll)
        else if (configured.nonEmpty) parseHosts(configured).map(base.withAllowOriginHost)
        else if (environment == "dev") IO.pure(base.withAllowOriginAll)
        else parseHosts(List(baseUrl.toString)).map(base.withAllowOriginHost)
      built.map(Some(_))
    }
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
