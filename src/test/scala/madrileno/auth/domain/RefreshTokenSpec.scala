package madrileno.auth.domain

import cats.effect.unsafe.implicits.global
import madrileno.support.TestData
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.time.{Duration, Instant}

class RefreshTokenSpec extends AnyWordSpec with Matchers {
  private val now = Instant.now()

  "RefreshToken.isValid" should {
    "be valid when freshly minted" in {
      val token = TestData.refreshToken()
      token.isValid(now) shouldBe true
    }

    "be invalid after being used" in {
      val token = TestData.refreshToken().usedAt(now)
      token.isValid(now) shouldBe false
    }

    "be invalid after being deleted" in {
      val token = TestData.refreshToken().deletedAt(now)
      token.isValid(now) shouldBe false
    }

    "be invalid when both used and deleted" in {
      val token = TestData.refreshToken().usedAt(now).deletedAt(now)
      token.isValid(now) shouldBe false
    }

    "be valid before expiresAt" in {
      val token = TestData.refreshToken(expiresAt = now.plusSeconds(60))
      token.isValid(now) shouldBe true
    }

    "be invalid at expiresAt" in {
      val token = TestData.refreshToken(expiresAt = now)
      token.isValid(now) shouldBe false
    }

    "be invalid after expiresAt" in {
      val token = TestData.refreshToken(expiresAt = now.minusSeconds(1))
      token.isValid(now) shouldBe false
    }
  }

  "RefreshToken.isUsed" should {
    "be false for a fresh token and true once used" in {
      val token = TestData.refreshToken()
      token.isUsed shouldBe false
      token.usedAt(now).isUsed shouldBe true
    }
  }

  "RefreshToken.mint" should {
    "create a valid token expiring at now + validFor" in {
      val id        = TestData.randomRefreshTokenId()
      val familyId  = TestData.randomRefreshTokenFamilyId()
      val secret    = TestData.refreshTokenSecret()
      val userId    = TestData.randomUserId()
      val userAgent = UserAgent("test-browser")
      val ip        = TestData.defaultIpAddress

      val token = RefreshToken.mint(id, familyId, secret.hash, now, userId, userAgent, ip, validFor = Duration.ofDays(30))

      token.id shouldBe id
      token.familyId shouldBe familyId
      token.secretHash shouldBe secret.hash
      token.userId shouldBe userId
      token.userAgent shouldBe userAgent
      token.ipAddress shouldBe ip
      token.createdAt shouldBe now
      token.expiresAt shouldBe now.plus(Duration.ofDays(30))
      token.isValid(now) shouldBe true
      token.isValid(now.plus(Duration.ofDays(31))) shouldBe false
    }
  }

  "RefreshTokenSecret" should {
    "generate distinct, url-safe secrets of 256 bits" in {
      val a = RefreshTokenSecret.generate.unsafeRunSync()
      val b = RefreshTokenSecret.generate.unsafeRunSync()
      a should not be b
      a.toString should have length 43
      a.toString should fullyMatch regex "[A-Za-z0-9_-]+"
    }

    "hash deterministically and never equal the secret itself" in {
      val secret = RefreshTokenSecret.generate.unsafeRunSync()
      secret.hash shouldBe secret.hash
      secret.hash.toString should not be secret.toString
      RefreshTokenSecret("other").hash should not be secret.hash
    }

    "reject empty strings" in {
      assertThrows[IllegalArgumentException] {
        RefreshTokenSecret("")
      }
    }
  }

  "UserAgent" should {
    "accept valid strings" in {
      UserAgent("Mozilla/5.0") shouldBe a[UserAgent]
    }

    "trim whitespace" in {
      UserAgent("  Chrome  ").toString shouldBe "Chrome"
    }

    "reject empty strings" in {
      assertThrows[IllegalArgumentException] {
        UserAgent("")
      }
    }

    "reject blank strings" in {
      assertThrows[IllegalArgumentException] {
        UserAgent("   ")
      }
    }
  }
}
