package madrileno.utils.task

import org.scalatest.OptionValues
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.time.Instant

class CronExpressionSpec extends AnyWordSpec with Matchers with OptionValues {

  "CronExpression.parse" should {
    "parse a valid cron expression" in {
      CronExpression.parse("0 0 1 * * ?") shouldBe a[Right[?, ?]]
    }

    "reject an invalid cron expression" in {
      CronExpression.parse("not a cron") shouldBe a[Left[?, ?]]
    }

    "reject an empty string" in {
      CronExpression.parse("") shouldBe a[Left[?, ?]]
    }
  }

  "CronExpression.unsafeParse" should {
    "return a CronExpression for valid input" in {
      noException should be thrownBy CronExpression.unsafeParse("0 0 1 * * ?")
    }

    "throw for invalid input" in {
      assertThrows[IllegalArgumentException] {
        CronExpression.unsafeParse("bad")
      }
    }
  }

  "CronExpression.nextFrom" should {
    "compute the next execution time" in {
      val cron = CronExpression.unsafeParse("0 0 1 * * ?") // daily at 1:00
      val now  = Instant.parse("2026-04-05T00:00:00Z")

      cron.nextFrom(now).value shouldBe Instant.parse("2026-04-05T01:00:00Z")
    }

    "advance to the next day if past the time" in {
      val cron = CronExpression.unsafeParse("0 0 1 * * ?")
      val now  = Instant.parse("2026-04-05T02:00:00Z")

      cron.nextFrom(now).value shouldBe Instant.parse("2026-04-06T01:00:00Z")
    }

    "handle every-5-minutes expression" in {
      val cron = CronExpression.unsafeParse("0 */5 * * * ?")
      val now  = Instant.parse("2026-04-05T10:03:00Z")

      cron.nextFrom(now).value shouldBe Instant.parse("2026-04-05T10:05:00Z")
    }

    "use UTC by default" in {
      val cron = CronExpression.unsafeParse("0 0 0 * * ?") // midnight
      val now  = Instant.parse("2026-04-05T23:30:00Z")

      cron.nextFrom(now).value shouldBe Instant.parse("2026-04-06T00:00:00Z")
    }
  }
}
