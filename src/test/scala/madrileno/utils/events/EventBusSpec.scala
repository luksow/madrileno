package madrileno.utils.events

import cats.effect.IO
import cats.effect.testing.scalatest.AsyncIOSpec
import cats.syntax.all.*
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec

import scala.concurrent.duration.*

class EventBusSpec extends AsyncWordSpec with AsyncIOSpec with Matchers {

  private case class Event(value: Int) derives EventCodec

  "EventBusRuntime.local" should {
    "fan out published events to every subscriber" in {
      val bus = EventBusRuntime.local.topic[Event]("test", maxQueued = 64)
      for {
        subA <- bus.subscribe.take(3).compile.toList.start
        subB <- bus.subscribe.take(3).compile.toList.start
        _    <- IO.sleep(50.millis) // let subscribers register
        _    <- (1 to 3).toList.traverse(i => bus.publish(Event(i)))
        a    <- subA.joinWithNever
        b    <- subB.joinWithNever
      } yield {
        a.map(_.value) shouldBe List(1, 2, 3)
        b.map(_.value) shouldBe List(1, 2, 3)
      }
    }

    "not replay events to subscribers that connect later" in {
      val bus = EventBusRuntime.local.topic[Event]("test", maxQueued = 64)
      for {
        _     <- bus.publish(Event(1)) // published before anyone subscribes
        late  <- bus.subscribe.take(1).compile.toList.timeout(200.millis).attempt.start
        _     <- IO.sleep(50.millis)
        _     <- bus.publish(Event(2))
        after <- late.joinWithNever
      } yield after.map(_.map(_.value)) shouldBe Right(List(2))
    }

    "each topic[E] call returns independent buses" in {
      val runtime = EventBusRuntime.local
      val a       = runtime.topic[Event]("a", maxQueued = 64)
      val b       = runtime.topic[Event]("b", maxQueued = 64)
      for {
        subA <- a.subscribe.take(1).compile.toList.start
        _    <- IO.sleep(50.millis)
        _    <- b.publish(Event(99))
        _    <- a.publish(Event(42))
        out  <- subA.joinWithNever
      } yield out.map(_.value) shouldBe List(42)
    }
  }
}
