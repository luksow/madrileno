package madrileno.utils.events

import cats.effect.IO
import cats.effect.std.Supervisor
import cats.effect.testing.scalatest.AsyncIOSpec
import io.circe.Codec
import io.circe.generic.semiauto.deriveCodec
import madrileno.support.TestTransactor
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec

import scala.concurrent.duration.*

class PostgresEventBusSpec extends AsyncWordSpec with AsyncIOSpec with Matchers with TestTransactor {

  private case class Sample(id: Int, name: String)
  private given Codec[Sample] = deriveCodec

  "EventBusRuntime.postgres" should {
    "deliver events across separate runtime instances over the same Postgres" in {
      Supervisor[IO].use { sup =>
        given Supervisor[IO] = sup
        val publisherBus     = EventBusRuntime.postgres(pgSessions).topic[Sample]("eventbus.crossinst.test")
        val subscriberBus    = EventBusRuntime.postgres(pgSessions).topic[Sample]("eventbus.crossinst.test")
        for {
          sub <- subscriberBus.subscribe.take(1).compile.lastOrError.start
          _   <- IO.sleep(300.millis)
          _   <- publisherBus.publish(Sample(1, "hello"))
          got <- sub.joinWithNever
        } yield got shouldBe Sample(1, "hello")
      }
    }

    "fan out a publish to multiple subscribers on a single runtime instance" in {
      Supervisor[IO].use { sup =>
        given Supervisor[IO] = sup
        val bus              = EventBusRuntime.postgres(pgSessions).topic[Sample]("eventbus.fanout.test")
        for {
          a  <- bus.subscribe.take(1).compile.lastOrError.start
          b  <- bus.subscribe.take(1).compile.lastOrError.start
          _  <- IO.sleep(300.millis)
          _  <- bus.publish(Sample(42, "broadcast"))
          ra <- a.joinWithNever
          rb <- b.joinWithNever
        } yield {
          ra shouldBe Sample(42, "broadcast")
          rb shouldBe Sample(42, "broadcast")
        }
      }
    }
  }
}
