package madrileno.user.domain

import madrileno.auth.domain.ExternalProfile
import madrileno.support.TestData
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.net.URI
import java.time.Instant

class UserSpec extends AnyWordSpec with Matchers {

  "User" should {
    "be active when not blocked" in {
      TestData.user().isActive shouldBe true
    }

    "be inactive when blocked" in {
      TestData.user(blockedAt = Some(Instant.now())).isActive shouldBe false
    }

    "update profile from external provider — fills in missing fields" in {
      val user = TestData.user(fullName = None, emailAddress = None, avatarUrl = None, emailVerified = false)
      val profile = ExternalProfile(
        fullName = Some(FullName("New Name")),
        emailAddress = Some(EmailAddress("new@example.com")),
        emailVerified = true,
        avatarUrl = Some(URI("https://example.com/avatar.png"))
      )

      val updated = user.withUpdatedProfile(profile)

      updated.fullName shouldBe Some(FullName("New Name"))
      updated.emailAddress shouldBe Some(EmailAddress("new@example.com"))
      updated.emailVerified shouldBe true
      updated.avatarUrl shouldBe Some(URI("https://example.com/avatar.png"))
    }

    "update profile — preserves existing fields when external has None" in {
      val user = TestData.user(
        fullName = Some(FullName("Existing")),
        emailAddress = Some(EmailAddress("existing@example.com")),
        avatarUrl = Some(URI("https://example.com/old.png"))
      )
      val profile = ExternalProfile(fullName = None, emailAddress = None, emailVerified = false, avatarUrl = None)

      val updated = user.withUpdatedProfile(profile)

      updated.fullName shouldBe Some(FullName("Existing"))
      updated.emailAddress shouldBe Some(EmailAddress("existing@example.com"))
      updated.avatarUrl shouldBe Some(URI("https://example.com/old.png"))
    }

    "update profile — emailVerified is sticky (true stays true)" in {
      val user    = TestData.user(emailVerified = true)
      val profile = ExternalProfile(fullName = None, emailAddress = None, emailVerified = false, avatarUrl = None)

      user.withUpdatedProfile(profile).emailVerified shouldBe true
    }

    "update profile — emailVerified upgrades from false to true" in {
      val user    = TestData.user(emailVerified = false)
      val profile = ExternalProfile(fullName = None, emailAddress = None, emailVerified = true, avatarUrl = None)

      user.withUpdatedProfile(profile).emailVerified shouldBe true
    }
  }

  "EmailAddress" should {
    "accept valid addresses" in {
      EmailAddress("user@example.com").toString shouldBe "user@example.com"
    }

    "reject strings without @" in {
      assertThrows[IllegalArgumentException](EmailAddress("invalid"))
    }

    "reject too-short strings" in {
      assertThrows[IllegalArgumentException](EmailAddress("a@"))
    }
  }

  "FullName" should {
    "accept non-empty strings" in {
      FullName("Alice").toString shouldBe "Alice"
    }

    "trim whitespace" in {
      FullName("  Bob  ").toString shouldBe "Bob"
    }

    "reject empty strings" in {
      assertThrows[IllegalArgumentException](FullName(""))
    }
  }
}
