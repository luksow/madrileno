package madrileno.auth.services

import cats.effect.IO
import io.circe.derivation.{Configuration, ConfiguredCodec}
import sttp.capabilities.fs2.Fs2Streams
import sttp.client4.circe.*
import sttp.client4.{UriContext, WebSocketStreamBackend, basicRequest}
import sttp.model.Uri

object OidcDiscovery {
  private given Configuration = Configuration.default
  private final case class Metadata(issuer: String, jwks_uri: String) derives ConfiguredCodec

  def jwksUri(issuer: String, http: WebSocketStreamBackend[IO, Fs2Streams[IO]]): IO[Uri] = {
    val discoveryUri = uri"${issuer.stripSuffix("/")}/.well-known/openid-configuration"
    basicRequest.get(discoveryUri).response(asJson[Metadata]).send(http).flatMap { response =>
      response.body match {
        case Right(metadata) if metadata.issuer != issuer =>
          IO.raiseError(new IllegalStateException(s"OIDC discovery: issuer mismatch (configured '$issuer', server '${metadata.issuer}')"))
        case Right(metadata) =>
          Uri
            .parse(metadata.jwks_uri)
            .fold(err => IO.raiseError(new IllegalArgumentException(s"OIDC discovery: invalid jwks_uri '${metadata.jwks_uri}': $err")), IO.pure)
        case Left(error) => IO.raiseError(error)
      }
    }
  }
}
