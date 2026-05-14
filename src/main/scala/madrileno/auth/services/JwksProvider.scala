package madrileno.auth.services

import cats.effect.{IO, Ref}
import io.circe.derivation.{Configuration, ConfiguredCodec}
import madrileno.utils.cache.CacheRuntime
import sttp.capabilities.fs2.Fs2Streams
import sttp.client4.circe.*
import sttp.client4.{WebSocketStreamBackend, basicRequest}
import sttp.model.Uri

import java.math.BigInteger
import java.security.KeyFactory
import java.security.interfaces.RSAPublicKey
import java.security.spec.RSAPublicKeySpec
import java.util.Base64
import scala.concurrent.duration.*

final class JwksProvider(
  jwksUri: IO[Uri],
  http: WebSocketStreamBackend[IO, Fs2Streams[IO]],
  cacheRuntime: CacheRuntime) {
  import JwksProvider.*

  private val cache    = cacheRuntime.expiring[Unit, Map[String, RSAPublicKey]](expireAfterWrite = 1.hour, maxSize = 1)
  private val uriCache = Ref.unsafe[IO, Option[Uri]](None)

  // While the keys cache is fresh, reject unknown kids without refetching — the route is unauthenticated, so
  // a refetch-on-unknown-kid path lets random tokens force outbound JWKS / discovery calls. Kid rotation picks
  // up the new key on the next cache-expiry refresh (1h window).
  def keyFor(keyId: String): IO[RSAPublicKey] =
    cache.get(()).flatMap {
      case Some(keys) =>
        IO.fromOption(keys.get(keyId))(unknownKid(keyId))
      case None =>
        fetchJwks
          .flatTap(keys => cache.put((), keys))
          .flatMap(keys => IO.fromOption(keys.get(keyId))(unknownKid(keyId)))
    }

  private def unknownKid(keyId: String): Throwable =
    new IllegalArgumentException(s"unknown JWKS key id '$keyId'")

  // Memoize the resolved URI so OIDC discovery (when used) runs once per JwksProvider lifetime rather than
  // on every JWKS refresh. A small race on first miss is fine — both branches resolve to the same URI.
  private def resolveUri: IO[Uri] =
    uriCache.get.flatMap {
      case Some(uri) => IO.pure(uri)
      case None      => jwksUri.flatTap(uri => uriCache.set(Some(uri)))
    }

  private def fetchJwks: IO[Map[String, RSAPublicKey]] =
    resolveUri.flatMap { uri =>
      basicRequest.get(uri).response(asJson[JwksDocument]).send(http).flatMap { response =>
        response.body match {
          case Right(document) => parseKeys(document.keys)
          case Left(error)     => IO.raiseError(error)
        }
      }
    }

  private def parseKeys(jwks: List[Jwk]): IO[Map[String, RSAPublicKey]] =
    IO.delay {
      jwks.collect { case Jwk(Some(kid), "RSA", Some(n), Some(e)) =>
        val modulus  = new BigInteger(1, Base64.getUrlDecoder.decode(n))
        val exponent = new BigInteger(1, Base64.getUrlDecoder.decode(e))
        KeyFactory.getInstance("RSA").generatePublic(new RSAPublicKeySpec(modulus, exponent)) match {
          case rsa: RSAPublicKey => kid -> rsa
          case other             => throw new IllegalStateException(s"JWKS key '$kid' is not RSA (${other.getAlgorithm})")
        }
      }.toMap
    }
}

object JwksProvider {
  private given Configuration = Configuration.default

  private final case class JwksDocument(keys: List[Jwk]) derives ConfiguredCodec
  private final case class Jwk(
    kid: Option[String],
    kty: String,
    n: Option[String],
    e: Option[String])
      derives ConfiguredCodec
}
