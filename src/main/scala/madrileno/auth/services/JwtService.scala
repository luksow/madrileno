package madrileno.auth.services

import io.circe.*
import pdi.jwt.exceptions.JwtExpirationException
import pdi.jwt.{Jwt, JwtAlgorithm, JwtClaim}
import JwtService.*
import pureconfig.*
import pureconfig.generic.derivation.default.*
import java.time.Instant
import scala.util.{Failure, Success}
import java.time.Duration

class JwtService(config: JwtService.Config) {
  private val algorithm = JwtAlgorithm.HS256

  def decode(jwt: String): DecodingResult =
    Jwt.decode(jwt, config.secret, Seq(algorithm)) match {
      case Success(payload) =>
        parser.parse(payload.content) match {
          case Right(jsObject) => DecodingResult.Decoded(jsObject)
          case Left(t)         => DecodingResult.ParsingFailure(t)
        }
      case Failure(_: JwtExpirationException) => DecodingResult.Expired
      case Failure(t)                         => DecodingResult.InvalidToken(t)
    }

  def encode(
    payload: Json,
    issuer: Option[String] = None,
    subject: Option[String] = None,
    audience: Option[Set[String]] = None,
    expiresAt: Option[Instant] = None,
    notBefore: Option[Instant] = None,
    issuedAt: Option[Instant] = None,
    jti: Option[String] = None
  ): String =
    Jwt.encode(
      JwtClaim(
        payload.toString(),
        issuer = issuer,
        subject = subject,
        audience = audience,
        expiration = expiresAt.map(_.getEpochSecond()),
        notBefore = notBefore.map(_.getEpochSecond()),
        issuedAt = issuedAt.map(_.getEpochSecond()),
        jwtId = jti
      ),
      config.secret,
      algorithm
    )
}

object JwtService {
  case class Config(secret: String, validFor: Duration) derives ConfigReader

  enum DecodingResult {
    case Decoded(payload: Json)
    case ParsingFailure(t: io.circe.ParsingFailure)
    case InvalidToken(t: Throwable)
    case Expired
  }
}
