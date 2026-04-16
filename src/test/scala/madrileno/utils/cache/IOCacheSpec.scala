package madrileno.utils.cache

import cats.effect.testing.scalatest.AsyncIOSpec
import cats.effect.{Deferred, IO, Ref}
import cats.syntax.all.*
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec

import scala.concurrent.duration.*

class IOCacheSpec extends AsyncWordSpec with AsyncIOSpec with Matchers {

  private val longTtl: Any => FiniteDuration = _ => 1.hour

  "IOCache" should {
    "return cached value on hit" in {
      val cache = IOCache[String, Int](longTtl, maximumSize = 128)
      for {
        _   <- cache.put("k", 42)
        got <- cache.get("k")
      } yield got shouldBe Some(42)
    }

    "return None on miss" in {
      val cache = IOCache[String, Int](longTtl, maximumSize = 128)
      cache.get("absent").map(_ shouldBe None)
    }

    "run the loader exactly once for a miss" in {
      val cache = IOCache[String, Int](longTtl, maximumSize = 128)
      for {
        calls <- Ref.of[IO, Int](0)
        load = calls.updateAndGet(_ + 1)
        first  <- cache.getOrLoad("k")(load)
        second <- cache.getOrLoad("k")(load)
        third  <- cache.getOrLoad("k")(load)
        total  <- calls.get
      } yield {
        first shouldBe 1
        second shouldBe 1
        third shouldBe 1
        total shouldBe 1
      }
    }

    "dedup concurrent loaders for the same key" in {
      val cache = IOCache[String, Int](longTtl, maximumSize = 128)
      for {
        calls <- Ref.of[IO, Int](0)
        gate  <- Deferred[IO, Unit]
        load = calls.updateAndGet(_ + 1) <* gate.get
        fiber1 <- cache.getOrLoad("k")(load).start
        fiber2 <- cache.getOrLoad("k")(load).start
        fiber3 <- cache.getOrLoad("k")(load).start
        _      <- IO.sleep(50.millis) // let all three enter dedupLoad before completing
        _      <- gate.complete(())
        r1     <- fiber1.joinWithNever
        r2     <- fiber2.joinWithNever
        r3     <- fiber3.joinWithNever
        total  <- calls.get
      } yield {
        r1 shouldBe 1
        r2 shouldBe 1
        r3 shouldBe 1
        total shouldBe 1
      }
    }

    "not cache failures — next call retries" in {
      val cache = IOCache[String, Int](longTtl, maximumSize = 128)
      val boom  = new RuntimeException("boom")
      for {
        attempt <- Ref.of[IO, Int](0)
        load = attempt.updateAndGet(_ + 1).flatMap(n => if (n == 1) IO.raiseError(boom) else IO.pure(n))
        firstRes   <- cache.getOrLoad("k")(load).attempt
        secondRes  <- cache.getOrLoad("k")(load)
        thirdRes   <- cache.getOrLoad("k")(load)
        totalCalls <- attempt.get
      } yield {
        firstRes shouldBe Left(boom)
        secondRes shouldBe 2
        thirdRes shouldBe 2 // cached after recovery
        totalCalls shouldBe 2
      }
    }

    "propagate load failure to all waiters of the same key" in {
      val cache = IOCache[String, Int](longTtl, maximumSize = 128)
      val boom  = new RuntimeException("shared-boom")
      for {
        gate <- Deferred[IO, Unit]
        load = gate.get *> IO.raiseError[Int](boom)
        fiber1 <- cache.getOrLoad("k")(load).attempt.start
        fiber2 <- cache.getOrLoad("k")(load).attempt.start
        _      <- IO.sleep(50.millis)
        _      <- gate.complete(())
        r1     <- fiber1.joinWithNever
        r2     <- fiber2.joinWithNever
      } yield {
        r1 shouldBe Left(boom)
        r2 shouldBe Left(boom)
      }
    }

    "clear in-flight slot when the originating fiber is cancelled" in {
      val cache = IOCache[String, Int](longTtl, maximumSize = 128)
      for {
        attempts <- Ref.of[IO, Int](0)
        started  <- Deferred[IO, Unit]
        slowLoad = attempts.updateAndGet(_ + 1) *> started.complete(()) *> IO.never[Int]
        fastLoad = IO.pure(99)
        fiber <- cache.getOrLoad("k")(slowLoad).start
        _     <- started.get
        _     <- fiber.cancel
        // Waiting for the cancel to propagate through the guaranteeCase handlers.
        _     <- IO.sleep(50.millis)
        retry <- cache.getOrLoad("k")(fastLoad)
        total <- attempts.get
      } yield {
        retry shouldBe 99
        total shouldBe 1
      }
    }

    "honor per-value TTL (shorter for None than for Some)" in {
      val ttl: Option[Int] => FiniteDuration = {
        case None    => 100.millis
        case Some(_) => 10.seconds
      }
      val cache = IOCache[String, Option[Int]](ttl, maximumSize = 128)
      for {
        _            <- cache.put("absent", None)
        _            <- cache.put("present", Some(1))
        _            <- IO.sleep(250.millis)
        absentAfter  <- cache.get("absent")
        presentAfter <- cache.get("present")
      } yield {
        absentAfter shouldBe None // evicted (short TTL)
        presentAfter shouldBe Some(Some(1)) // still present (long TTL)
      }
    }

    "bound memory via maximumSize" in {
      val cache = IOCache[Int, Int](longTtl, maximumSize = 2)
      for {
        _       <- (1 to 100).toList.traverse_(i => cache.put(i, i * i))
        _       <- (1 to 100).toList.traverse_(i => cache.get(i)) // triggers Caffeine's opportunistic eviction
        present <- (1 to 100).toList.traverse(i => cache.get(i))
      } yield {
        // Caffeine bounds memory asymptotically — not exactly maximumSize, but well below total inserts.
        val survivors = present.count(_.isDefined)
        survivors should be <= 10
      }
    }

    "report accurate stats for hits, misses, and loads" in {
      val cache = IOCache[String, Int](longTtl, maximumSize = 128)
      for {
        _        <- cache.get("absent")
        _        <- cache.getOrLoad("k")(IO.pure(1))
        _        <- cache.getOrLoad("k")(IO.pure(1))
        _        <- cache.getOrLoad("k")(IO.pure(1))
        failures <- cache.getOrLoad("bad")(IO.raiseError[Int](new RuntimeException("nope"))).attempt
        stats    <- cache.stats
      } yield {
        failures.isLeft shouldBe true
        stats.missCount should be >= 2L // "absent" + first "k"
        stats.hitCount should be >= 2L // second and third "k"
        stats.loadSuccessCount shouldBe 1L
        stats.loadFailureCount shouldBe 1L
        stats.requestCount shouldBe (stats.hitCount + stats.missCount)
      }
    }

    "estimatedSize reflects current entry count" in {
      val cache = IOCache[Int, String](longTtl, maximumSize = 128)
      for {
        empty <- cache.estimatedSize
        _     <- (1 to 5).toList.traverse_(i => cache.put(i, s"v$i"))
        after <- cache.estimatedSize
      } yield {
        empty shouldBe 0L
        after shouldBe 5L
      }
    }

    "invalidateAll removes every entry" in {
      val cache = IOCache[Int, String](longTtl, maximumSize = 128)
      for {
        _      <- (1 to 5).toList.traverse_(i => cache.put(i, s"v$i"))
        before <- cache.estimatedSize
        _      <- cache.invalidateAll
        after  <- cache.estimatedSize
        got    <- cache.get(3)
      } yield {
        before shouldBe 5L
        after shouldBe 0L
        got shouldBe None
      }
    }
  }
}
