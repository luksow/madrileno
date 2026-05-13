package madrileno.auth.services

import cats.effect.IO
import cats.effect.testing.scalatest.AsyncIOSpec
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import madrileno.auth.domain.{Provider, ProviderUserId}
import madrileno.user.domain.{EmailAddress, FullName}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec

import java.security.KeyPairGenerator
import java.security.interfaces.{RSAPrivateKey, RSAPublicKey}
import java.time.Instant

class Rs256TokenVerifierSpec extends AsyncWordSpec with AsyncIOSpec with Matchers {
  private val issuer       = "https://issuer.example.com"
  private val audience     = "test-audience"
  private val providerName = Provider("test")
  private val testKid      = "test-kid"

  private val (publicKey, privateKey) = {
    val generator = KeyPairGenerator.getInstance("RSA")
    generator.initialize(2048)
    val pair = generator.generateKeyPair()
    val pub = pair.getPublic match {
      case rsa: RSAPublicKey => rsa
      case other             => throw new IllegalStateException(s"expected RSAPublicKey, got ${other.getAlgorithm}")
    }
    val priv = pair.getPrivate match {
      case rsa: RSAPrivateKey => rsa
      case other              => throw new IllegalStateException(s"expected RSAPrivateKey, got ${other.getAlgorithm}")
    }
    (pub, priv)
  }

  private val constantResolver: String => IO[RSAPublicKey] = _ => IO.pure(publicKey)
  private val defaultAlgorithm                             = Algorithm.RSA256(publicKey, privateKey)

  private def verifier(
    iss: String = issuer,
    aud: Set[String] = Set(audience),
    resolver: String => IO[RSAPublicKey] = constantResolver
  ): Rs256TokenVerifier =
    new Rs256TokenVerifier(providerName, iss, aud, resolver)

  private def signedToken(
    sub: String = "user-123",
    iss: String = issuer,
    aud: List[String] = List(audience),
    issuedAt: Instant = Instant.now(),
    expiresAt: Instant = Instant.now().plusSeconds(300),
    kid: String = testKid,
    name: Option[String] = None,
    email: Option[String] = None,
    emailVerified: Option[Boolean] = None,
    picture: Option[String] = None,
    algorithm: Algorithm = defaultAlgorithm
  ): String = {
    val builder = JWT
      .create()
      .withKeyId(kid)
      .withIssuer(iss)
      .withSubject(sub)
      .withAudience(aud*)
      .withIssuedAt(issuedAt)
      .withExpiresAt(expiresAt)
    name.foreach(builder.withClaim("name", _))
    email.foreach(builder.withClaim("email", _))
    emailVerified.foreach(v => builder.withClaim("email_verified", java.lang.Boolean.valueOf(v)))
    picture.foreach(builder.withClaim("picture", _))
    builder.sign(algorithm)
  }

  "Rs256TokenVerifier" should {
    "accept a well-formed token and surface OIDC claims" in {
      val token = signedToken(
        sub = "alice-uid",
        name = Some("Alice"),
        email = Some("alice@example.com"),
        emailVerified = Some(true),
        picture = Some("https://example.com/avatar.png")
      )
      verifier().verifyToken(token).asserting {
        case Right(v) =>
          v.provider shouldBe providerName
          v.providerUserId shouldBe ProviderUserId("alice-uid")
          v.profile.fullName shouldBe Some(FullName("Alice"))
          v.profile.emailAddress shouldBe Some(EmailAddress("alice@example.com"))
          v.profile.emailVerified shouldBe true
        case Left(t) => fail(s"expected Right, got Left($t)")
      }
    }

    "default email_verified to false when the claim is absent" in {
      verifier().verifyToken(signedToken(email = Some("nobody@example.com"))).asserting {
        case Right(v) => v.profile.emailVerified shouldBe false
        case Left(t)  => fail(s"expected Right, got Left($t)")
      }
    }

    "reject a token whose issuer does not match" in {
      verifier(iss = "https://other.example.com").verifyToken(signedToken()).asserting(_.isLeft shouldBe true)
    }

    "reject a token whose audience does not intersect the configured set" in {
      verifier(aud = Set("other-audience")).verifyToken(signedToken()).asserting(_.isLeft shouldBe true)
    }

    "accept a token whose audience array intersects the configured set" in {
      val token = signedToken(aud = List("other-audience", audience))
      verifier(aud = Set(audience)).verifyToken(token).asserting(_.isRight shouldBe true)
    }

    "reject a token signed with a different key" in {
      val otherGenerator = KeyPairGenerator.getInstance("RSA")
      otherGenerator.initialize(2048)
      val otherPriv = otherGenerator.generateKeyPair().getPrivate match {
        case rsa: RSAPrivateKey => rsa
        case other              => throw new IllegalStateException(s"expected RSAPrivateKey, got ${other.getAlgorithm}")
      }
      val token = signedToken(algorithm = Algorithm.RSA256(publicKey, otherPriv))
      verifier().verifyToken(token).asserting(_.isLeft shouldBe true)
    }

    "reject an expired token" in {
      val token = signedToken(issuedAt = Instant.parse("2020-01-01T00:00:00Z"), expiresAt = Instant.parse("2020-01-01T00:05:00Z"))
      verifier().verifyToken(token).asserting(_.isLeft shouldBe true)
    }

    "surface the key resolver failure when the kid is unknown" in {
      val resolver: String => IO[RSAPublicKey] = _ => IO.raiseError(new IllegalArgumentException("unknown kid"))
      verifier(resolver = resolver).verifyToken(signedToken()).asserting(_.isLeft shouldBe true)
    }
  }
}
