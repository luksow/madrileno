package madrileno.utils.cache

import cats.effect.IO
import cats.effect.testing.scalatest.AsyncIOSpec
import cats.syntax.all.*
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec

import scala.concurrent.duration.*

class CacheSpec extends AsyncWordSpec with AsyncIOSpec with Matchers {

  private def caches: List[(String, Cache[String, Int])] = List(
    "scaffeine" -> CacheRuntime.scaffeine.expiring[String, Int](expireAfterWrite = 1.hour, maxSize = 128),
    "inMemory"  -> CacheRuntime.inMemory.expiring[String, Int](expireAfterWrite = 1.hour, maxSize = 128)
  )

  caches.foreach { case (label, cache) =>
    s"Cache ($label)" should {
      "return None for a missing key" in {
        cache.get(s"$label-missing").map(_ shouldBe None)
      }

      "return the cached value after put" in {
        val key = s"$label-k1"
        for {
          _   <- cache.put(key, 42)
          got <- cache.get(key)
        } yield got shouldBe Some(42)
      }

      "invalidate removes the value for that key" in {
        val key = s"$label-k2"
        for {
          _   <- cache.put(key, 7)
          _   <- cache.invalidate(key)
          got <- cache.get(key)
          stillThere = got
        } yield stillThere shouldBe None
      }

      "getOrLoad returns cached value on hit without running load" in {
        val key = s"$label-k3"
        for {
          counter <- IO.ref(0)
          load = counter.updateAndGet(_ + 1)
          first  <- cache.getOrLoad(key)(load)
          second <- cache.getOrLoad(key)(load)
          third  <- cache.getOrLoad(key)(load)
          total  <- counter.get
        } yield {
          first shouldBe 1
          second shouldBe 1
          third shouldBe 1
          total shouldBe 1
        }
      }

      "size reflects the number of stored entries" in {
        for {
          c <- IO.pure(CacheRuntime.inMemory.expiring[Int, String](1.hour, maxSize = 128))
          _ <- (1 to 5).toList.traverse_(i => c.put(i, s"v$i"))
          s <- c.size
        } yield s shouldBe 5L
      }

      "invalidateAll clears every entry" in {
        for {
          c     <- IO.pure(CacheRuntime.inMemory.expiring[Int, String](1.hour, maxSize = 128))
          _     <- (1 to 5).toList.traverse_(i => c.put(i, s"v$i"))
          _     <- c.invalidateAll
          after <- c.size
          got   <- c.get(3)
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

    "cap memory at the configured maxSize" in {
      val cache = CacheRuntime.scaffeine.expiring[Int, Int](expireAfterWrite = 1.hour, maxSize = 2)
      for {
        _       <- (1 to 100).toList.traverse_(i => cache.put(i, i * i))
        _       <- (1 to 100).toList.traverse_(i => cache.get(i))
        present <- (1 to 100).toList.traverse(i => cache.get(i))
        survivors = present.count(_.isDefined)
      } yield survivors should be <= 10
    }
  }

  "CacheRuntime.inMemory" should {
    "ignore TTL and maxSize" in {
      val cache = CacheRuntime.inMemory.expiring[Int, Int](expireAfterWrite = 1.milli, maxSize = 1)
      for {
        _   <- (1 to 100).toList.traverse_(i => cache.put(i, i))
        _   <- IO.sleep(50.millis)
        got <- (1 to 100).toList.traverse(i => cache.get(i))
      } yield got.forall(_.isDefined) shouldBe true
    }
  }
}
