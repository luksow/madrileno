package madrileno.auth.domain

import madrileno.support.TestData
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.time.{Duration, Instant}

class RefreshTokenSpec extends AnyWordSpec with Matchers {
  private val now = Instant.now()

  "RefreshToken.isValid" should {
    "be valid when freshly minted with no expiry" in {
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
      val token = TestData.refreshToken(expiresAt = Some(now.plusSeconds(60)))
      token.isValid(now) shouldBe true
    }

    "be invalid at expiresAt" in {
      val token = TestData.refreshToken(expiresAt = Some(now))
      token.isValid(now) shouldBe false
    }

    "be invalid after expiresAt" in {
      val token = TestData.refreshToken(expiresAt = Some(now.minusSeconds(1)))
      token.isValid(now) shouldBe false
    }
  }

  "RefreshToken.mint" should {
    "create a valid token with no expiry when validFor is None" in {
      val id        = TestData.randomRefreshTokenId()
      val userId    = TestData.randomUserId()
      val userAgent = UserAgent("test-browser")
      val ip        = TestData.defaultIpAddress

      val token = RefreshToken.mint(id, now, userId, userAgent, ip, validFor = None)

      token.id shouldBe id
      token.userId shouldBe userId
      token.userAgent shouldBe userAgent
      token.ipAddress shouldBe ip
      token.createdAt shouldBe now
      token.expiresAt shouldBe None
      token.isValid(now) shouldBe true
    }

    "set expiresAt = now + validFor when provided" in {
      val token = RefreshToken.mint(
        TestData.randomRefreshTokenId(),
        now,
        TestData.randomUserId(),
        UserAgent("test"),
        TestData.defaultIpAddress,
        validFor = Some(Duration.ofDays(30))
      )
      token.expiresAt shouldBe Some(now.plus(Duration.ofDays(30)))
      token.isValid(now) shouldBe true
      token.isValid(now.plus(Duration.ofDays(31))) shouldBe false
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
