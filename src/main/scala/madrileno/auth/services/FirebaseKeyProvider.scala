package madrileno.auth.services

import cats.effect.{Clock, IO, Ref}
import sttp.capabilities.fs2.Fs2Streams
import sttp.client4.circe.*
import sttp.client4.{UriContext, WebSocketStreamBackend, basicRequest}
import sttp.model.Header

import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets.UTF_8
import java.security.cert.CertificateFactory
import java.security.interfaces.RSAPublicKey
import java.time.Instant
import scala.util.matching.Regex

final class FirebaseKeyProvider(http: WebSocketStreamBackend[IO, Fs2Streams[IO]]) {
  import FirebaseKeyProvider.*

  private val certsUri = uri"https://www.googleapis.com/robot/v1/metadata/x509/securetoken@system.gserviceaccount.com"
  private val cache    = Ref.unsafe[IO, Option[CachedCerts]](None)

  def keyFor(keyId: String): IO[RSAPublicKey] =
    Clock[IO].realTimeInstant.flatMap { now =>
      cache.get.flatMap {
        case Some(cached) if cached.expiresAt.isAfter(now) =>
          IO.fromOption(cached.keys.get(keyId))(unknownKid(keyId))
        case _ =>
          fetchCertificates
            .flatTap(fresh => cache.set(Some(fresh)))
            .flatMap(fresh => IO.fromOption(fresh.keys.get(keyId))(unknownKid(keyId)))
      }
    }

  private def unknownKid(keyId: String): Throwable =
    new IllegalArgumentException(s"unknown Firebase signing key id '$keyId'")

  private def fetchCertificates: IO[CachedCerts] =
    basicRequest.get(certsUri).response(asJson[Map[String, String]]).send(http).flatMap { response =>
      response.body match {
        case Right(pemByKid) =>
          for {
            keys      <- parseCertificates(pemByKid)
            fetchedAt <- Clock[IO].realTimeInstant
          } yield CachedCerts(fetchedAt.plusSeconds(extractMaxAge(response.headers)), keys)
        case Left(error) =>
          IO.raiseError(error)
      }
    }

  private def parseCertificates(pemByKid: Map[String, String]): IO[Map[String, RSAPublicKey]] =
    IO.delay {
      pemByKid.map { (keyId, pem) =>
        CertificateFactory.getInstance("X.509").generateCertificate(new ByteArrayInputStream(pem.getBytes(UTF_8))).getPublicKey match {
          case rsa: RSAPublicKey => keyId -> rsa
          case other             => throw new IllegalStateException(s"Firebase signing certificate '$keyId' is not RSA (${other.getAlgorithm})")
        }
      }
    }

  private def extractMaxAge(headers: Seq[Header]): Long = {
    val cacheControl = headers.find(_.name.equalsIgnoreCase("Cache-Control")).map(_.value).getOrElse("")
    maxAgeRegex.findFirstMatchIn(cacheControl).flatMap(m => m.group(1).toLongOption).getOrElse(fallbackMaxAgeSeconds)
  }
}

object FirebaseKeyProvider {
  private val maxAgeRegex: Regex          = """max-age\s*=\s*(\d+)""".r
  private val fallbackMaxAgeSeconds: Long = 3600L

  private final case class CachedCerts(expiresAt: Instant, keys: Map[String, RSAPublicKey])
}
