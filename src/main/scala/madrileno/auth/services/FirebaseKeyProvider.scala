package madrileno.auth.services

import cats.effect.IO
import madrileno.utils.cache.CacheRuntime
import sttp.capabilities.fs2.Fs2Streams
import sttp.client4.circe.*
import sttp.client4.{UriContext, WebSocketStreamBackend, basicRequest}

import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets.UTF_8
import java.security.cert.CertificateFactory
import java.security.interfaces.RSAPublicKey
import scala.concurrent.duration.*

final class FirebaseKeyProvider(http: WebSocketStreamBackend[IO, Fs2Streams[IO]], cacheRuntime: CacheRuntime) {
  private val certsUri = uri"https://www.googleapis.com/robot/v1/metadata/x509/securetoken@system.gserviceaccount.com"
  private val cache    = cacheRuntime.expiring[Unit, Map[String, RSAPublicKey]](expireAfterWrite = 1.hour, maxSize = 1)

  def keyFor(keyId: String): IO[RSAPublicKey] =
    cache.get(()).flatMap {
      case Some(certificates) if certificates.contains(keyId) => IO.pure(certificates(keyId))
      case _ =>
        fetchCertificates
          .flatTap(certificates => cache.put((), certificates))
          .flatMap(certificates => IO.fromOption(certificates.get(keyId))(new IllegalArgumentException(s"unknown Firebase signing key id '$keyId'")))
    }

  private def fetchCertificates: IO[Map[String, RSAPublicKey]] =
    basicRequest.get(certsUri).response(asJson[Map[String, String]]).send(http).flatMap { response =>
      response.body match {
        case Right(pemByKeyId) => parseCertificates(pemByKeyId)
        case Left(error)       => IO.raiseError(error)
      }
    }

  private def parseCertificates(pemByKeyId: Map[String, String]): IO[Map[String, RSAPublicKey]] =
    IO.delay {
      pemByKeyId.map { (keyId, pem) =>
        CertificateFactory.getInstance("X.509").generateCertificate(new ByteArrayInputStream(pem.getBytes(UTF_8))).getPublicKey match {
          case rsa: RSAPublicKey => keyId -> rsa
          case other             => throw new IllegalStateException(s"Firebase signing certificate '$keyId' is not RSA (${other.getAlgorithm})")
        }
      }
    }
}
