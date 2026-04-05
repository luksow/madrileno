package madrileno.auth.domain

import madrileno.support.TestData
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.time.Instant

class RefreshTokenSpec extends AnyWordSpec with Matchers {
  private val now = Instant.now()

  "RefreshToken" should {
    "be valid when freshly minted" in {
      val token = TestData.refreshToken()
      token.isValid shouldBe true
    }

    "be invalid after being used" in {
      val token = TestData.refreshToken().usedAt(now)
      token.isValid shouldBe false
    }

    "be invalid after being deleted" in {
      val token = TestData.refreshToken().deletedAt(now)
      token.isValid shouldBe false
    }

    "be invalid when both used and deleted" in {
      val token = TestData.refreshToken().usedAt(now).deletedAt(now)
      token.isValid shouldBe false
    }

    "mint creates a valid token with correct fields" in {
      val id        = TestData.randomRefreshTokenId()
      val userId    = TestData.randomUserId()
      val userAgent = UserAgent("test-browser")
      val ip        = TestData.defaultIpAddress

      val token = RefreshToken.mint(id, now, userId, userAgent, ip)

      token.id shouldBe id
      token.userId shouldBe userId
      token.userAgent shouldBe userAgent
      token.ipAddress shouldBe ip
      token.createdAt shouldBe now
      token.isValid shouldBe true
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
