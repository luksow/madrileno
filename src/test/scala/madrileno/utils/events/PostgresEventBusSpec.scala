package madrileno.utils.events

import cats.effect.IO
import cats.effect.std.Supervisor
import cats.effect.testing.scalatest.AsyncIOSpec
import madrileno.support.TestTransactor
import madrileno.utils.observability.TelemetryContext
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import org.typelevel.otel4s.metrics.Meter
import org.typelevel.otel4s.trace.Tracer

import scala.concurrent.duration.*

class PostgresEventBusSpec extends AsyncWordSpec with AsyncIOSpec with Matchers with TestTransactor {

  private given TelemetryContext = TelemetryContext(Meter.noop[IO], Tracer.noop[IO], io.opentelemetry.api.OpenTelemetry.noop())

  private case class Sample(id: Int, name: String) derives EventCodec

  "EventBusRuntime.postgres" should {
    "deliver events across separate runtime instances over the same Postgres" in {
      Supervisor[IO].use { sup =>
        given Supervisor[IO] = sup
        val publisherBus     = EventBusRuntime.postgres(transactor).topic[Sample]("eventbus_crossinst_test", maxQueued = 64)
        val subscriberBus    = EventBusRuntime.postgres(transactor).topic[Sample]("eventbus_crossinst_test", maxQueued = 64)
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
        val bus              = EventBusRuntime.postgres(transactor).topic[Sample]("eventbus_fanout_test", maxQueued = 64)
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
