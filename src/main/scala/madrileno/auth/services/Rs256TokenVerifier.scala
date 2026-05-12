package madrileno.auth.services

import cats.effect.IO
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import io.circe.parser
import madrileno.auth.domain.*
import madrileno.user.domain.*

import java.net.URI
import java.nio.charset.StandardCharsets.UTF_8
import java.security.interfaces.{RSAPrivateKey, RSAPublicKey}
import java.util.Base64
import scala.jdk.CollectionConverters.*

final class Rs256TokenVerifier(
  provider: Provider,
  issuer: String,
  audience: Set[String],
  keyResolver: String => IO[RSAPublicKey],
  leewaySeconds: Long = 30L)
    extends ExternalAuthVerifier {

  override def verifyToken(token: String): IO[Either[Throwable, VerifiedExternalToken]] =
    (for {
      unverified <- IO.delay(JWT.decode(token))
      keyId      <- IO.fromOption(Option(unverified.getKeyId).filter(_.nonEmpty))(badToken("missing 'kid' header"))
      _          <- IO.raiseUnless(unverified.getAlgorithm == "RS256")(badToken(s"unexpected signature algorithm '${unverified.getAlgorithm}'"))
      publicKey  <- keyResolver(keyId)
      verified   <- IO.delay(JWT.require(rsa256(publicKey)).withIssuer(issuer).acceptLeeway(leewaySeconds).build().verify(token))
      tokenAudience = Option(verified.getAudience).map(_.asScala.toSet).getOrElse(Set.empty[String])
      _ <- IO.raiseUnless(audience.isEmpty || tokenAudience.exists(audience))(badToken(s"audience $tokenAudience does not include any of $audience"))
      subject <- IO.fromOption(Option(verified.getSubject).filter(_.nonEmpty))(badToken("missing 'sub' claim"))
      claims  <- IO.fromEither(parser.parse(new String(Base64.getUrlDecoder.decode(verified.getPayload), UTF_8)))
    } yield {
      VerifiedExternalToken(
        provider,
        ProviderUserId(subject),
        Credential(subject),
        ExternalProfile(
          Option(verified.getClaim("name").asString).map(FullName.apply),
          Option(verified.getClaim("email").asString).map(EmailAddress.apply),
          Option(verified.getClaim("email_verified").asBoolean).exists(_.booleanValue),
          Option(verified.getClaim("picture").asString).map(URI.create)
        ),
        Metadata(claims)
      )
    }).attempt

  private def rsa256(publicKey: RSAPublicKey): Algorithm =
    Algorithm.RSA256(publicKey, Option.empty[RSAPrivateKey].orNull)

  private def badToken(reason: String): Throwable =
    new IllegalArgumentException(s"$provider ID token: $reason")
}
