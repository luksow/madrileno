package madrileno.utils.events

import cats.effect.IO
import cats.effect.testing.scalatest.AsyncIOSpec
import cats.syntax.all.*
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec

import scala.concurrent.duration.*

class EventBusSpec extends AsyncWordSpec with AsyncIOSpec with Matchers {

  private case class Event(value: Int) derives EventCodec

  private val testTimeout: FiniteDuration = 5.seconds

  "EventBusRuntime.local" should {
    "fan out published events to every subscriber" in {
      val bus = EventBusRuntime.local.topic[Event]("test", maxQueued = 64)
      (bus.subscribeAwait, bus.subscribeAwait).tupled
        .use { case (sa, sb) =>
          for {
            subA <- sa.take(3).compile.toList.start
            subB <- sb.take(3).compile.toList.start
            _    <- (1 to 3).toList.traverse(i => bus.publish(Event(i)))
            a    <- subA.joinWithNever
            b    <- subB.joinWithNever
          } yield {
            a.map(_.value) shouldBe List(1, 2, 3)
            b.map(_.value) shouldBe List(1, 2, 3)
          }
        }
        .timeout(testTimeout)
    }

    "not replay events to subscribers that connect later" in {
      val bus = EventBusRuntime.local.topic[Event]("test", maxQueued = 64)
      (for {
        _ <- bus.publish(Event(1)) // published before anyone subscribes
        after <- bus.subscribeAwait.use { stream =>
                   bus.publish(Event(2)) *> stream.take(1).compile.toList
                 }
      } yield after.map(_.value) shouldBe List(2)).timeout(testTimeout)
    }

    "each topic[E] call returns independent buses" in {
      val runtime = EventBusRuntime.local
      val a       = runtime.topic[Event]("a", maxQueued = 64)
      val b       = runtime.topic[Event]("b", maxQueued = 64)
      a.subscribeAwait
        .use { stream =>
          for {
            subA <- stream.take(1).compile.toList.start
            _    <- b.publish(Event(99))
            _    <- a.publish(Event(42))
            out  <- subA.joinWithNever
          } yield out.map(_.value) shouldBe List(42)
        }
        .timeout(testTimeout)
    }
  }
}
