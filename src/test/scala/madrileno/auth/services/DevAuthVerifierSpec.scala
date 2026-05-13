package madrileno.auth.services

import cats.effect.testing.scalatest.AsyncIOSpec
import madrileno.auth.domain.{ExternalAuthToken, Provider, ProviderUserId}
import madrileno.user.domain.EmailAddress
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec

class DevAuthVerifierSpec extends AsyncWordSpec with AsyncIOSpec with Matchers {
  "DevAuthVerifier" should {
    "synthesise a verified token from an email address" in {
      DevAuthVerifier.verifyToken(ExternalAuthToken("  alice@example.com  ")).asserting {
        case Right(token) =>
          token.provider shouldBe Provider.Dev
          token.providerUserId shouldBe ProviderUserId("alice@example.com")
          token.profile.emailAddress shouldBe Some(EmailAddress("alice@example.com"))
          token.profile.emailVerified shouldBe true
        case Left(t) => fail(s"expected Right, got Left($t)")
      }
    }

    "reject anything that is not an email address" in {
      DevAuthVerifier.verifyToken(ExternalAuthToken("not-an-email")).asserting(_.isLeft shouldBe true)
    }
  }
}
