package madrileno.auth.services

import io.circe.*
import madrileno.auth.domain.InternalJwt
import pdi.jwt.exceptions.JwtExpirationException
import pdi.jwt.{Jwt, JwtAlgorithm, JwtClaim}
import pureconfig.*

import java.time.{Duration, Instant}
import scala.util.{Failure, Success}

import JwtService.*

class JwtService(config: JwtService.Config) {
  private val algorithm = JwtAlgorithm.HS256

  def decode[A](jwt: String)(using decoder: Decoder[A]): DecodingResult[A] =
    Jwt.decode(jwt, config.secret, Seq(algorithm)) match {
      case Success(payload) =>
        parser.parse(payload.content) match {
          case Right(jsObject) =>
            decoder.decodeJson(jsObject) match {
              case Right(value) => DecodingResult.Decoded(value)
              case Left(t)      => DecodingResult.ParsingFailure(t)
            }
          case Left(t) => DecodingResult.ParsingFailure(t)
        }
      case Failure(t: JwtExpirationException) => DecodingResult.Expired(t)
      case Failure(t)                         => DecodingResult.InvalidToken(t)
    }

  def encode[A](
    payload: A,
    now: Instant,
    issuer: Option[String] = None,
    subject: Option[String] = None,
    audience: Option[Set[String]] = None,
    notBefore: Option[Instant] = None,
    jti: Option[String] = None
  )(using encoder: Encoder[A]
  ): InternalJwt = {
    InternalJwt(
      Jwt.encode(
        JwtClaim(
          encoder.apply(payload).toString,
          issuer = issuer,
          subject = subject,
          audience = audience,
          expiration = Some(now.plus(config.validFor).getEpochSecond),
          notBefore = notBefore.map(_.getEpochSecond),
          issuedAt = Some(now.getEpochSecond),
          jwtId = jti
        ),
        config.secret,
        algorithm
      )
    )
  }
}

object JwtService {
  final case class Config(secret: String, validFor: Duration) derives ConfigReader

  enum DecodingResult[A] {
    case Decoded(payload: A)
    case ParsingFailure(t: Throwable)
    case InvalidToken(t: Throwable)
    case Expired(t: Throwable)
  }
}
