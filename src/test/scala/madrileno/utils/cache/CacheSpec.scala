package madrileno.utils.cache

import cats.effect.IO
import cats.effect.testing.scalatest.AsyncIOSpec
import cats.syntax.all.*
import madrileno.support.TestCacheRuntime
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec

import scala.concurrent.duration.*

class CacheSpec extends AsyncWordSpec with AsyncIOSpec with Matchers {

  private val runtimes: List[(String, CacheRuntime)] = List("scaffeine" -> CacheRuntime.scaffeine, "test" -> TestCacheRuntime.unbounded)

  runtimes.foreach { case (label, runtime) =>
    s"Cache ($label)" should {
      def newCache[K, V]: Cache[K, V] = runtime.expiring[K, V](expireAfterWrite = 1.hour, maxSize = 128)

      "return None for a missing key" in {
        newCache[String, Int].get("missing").map(_ shouldBe None)
      }

      "return the cached value after put" in {
        val cache = newCache[String, Int]
        for {
          _   <- cache.put("k", 42)
          got <- cache.get("k")
        } yield got shouldBe Some(42)
      }

      "invalidate removes the value for that key" in {
        val cache = newCache[String, Int]
        for {
          _   <- cache.put("k", 7)
          _   <- cache.invalidate("k")
          got <- cache.get("k")
        } yield got shouldBe None
      }

      "size reflects the number of stored entries" in {
        val cache = newCache[Int, String]
        for {
          _ <- (1 to 5).toList.traverse_(i => cache.put(i, s"v$i"))
          s <- cache.size
        } yield s shouldBe 5L
      }

      "invalidateAll clears every entry" in {
        val cache = newCache[Int, String]
        for {
          _     <- (1 to 5).toList.traverse_(i => cache.put(i, s"v$i"))
          _     <- cache.invalidateAll
          after <- cache.size
          got   <- cache.get(3)
        } yield {
          after shouldBe 0L
          got shouldBe None
        }
      }
    }
  }

  "CacheRuntime.scaffeine" should {
    "evict entries after the configured TTL elapses" in {
      val cache = CacheRuntime.scaffeine.expiring[String, Int](expireAfterWrite = 100.millis, maxSize = 128)
      for {
        _           <- cache.put("k", 1)
        fresh       <- cache.get("k")
        _           <- IO.sleep(250.millis)
        afterExpiry <- cache.get("k")
      } yield {
        fresh shouldBe Some(1)
        afterExpiry shouldBe None
      }
    }

  }

  "TestCacheRuntime.unbounded" should {
    "ignore TTL and maxSize" in {
      val cache = TestCacheRuntime.unbounded.expiring[Int, Int](expireAfterWrite = 1.milli, maxSize = 1)
      for {
        _   <- (1 to 100).toList.traverse_(i => cache.put(i, i))
        _   <- IO.sleep(50.millis)
        got <- (1 to 100).toList.traverse(i => cache.get(i))
      } yield got.forall(_.isDefined) shouldBe true
    }
  }
}
