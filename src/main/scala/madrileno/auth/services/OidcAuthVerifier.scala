package madrileno.auth.services

import cats.effect.IO
import madrileno.auth.OidcProviderConfig
import madrileno.auth.domain.Provider
import madrileno.utils.cache.CacheRuntime
import sttp.capabilities.fs2.Fs2Streams
import sttp.client4.WebSocketStreamBackend
import sttp.model.Uri

object OidcAuthVerifier {
  def apply(
    provider: Provider,
    config: OidcProviderConfig,
    http: WebSocketStreamBackend[IO, Fs2Streams[IO]],
    cacheRuntime: CacheRuntime
  ): ExternalAuthVerifier = {
    val jwksUri: IO[Uri] = config.jwksUri match {
      case Some(uri) =>
        Uri
          .parse(uri)
          .fold(err => IO.raiseError(new IllegalArgumentException(s"oidc provider '$provider' has invalid jwks-uri '$uri': $err")), IO.pure)
      case None =>
        OidcDiscovery.jwksUri(config.issuer, http)
    }
    val jwks     = new JwksProvider(jwksUri, http, cacheRuntime)
    val audience = config.audience.split(",").iterator.map(_.trim).filter(_.nonEmpty).toSet
    new Rs256TokenVerifier(provider, config.issuer, audience, jwks.keyFor)
  }
}
