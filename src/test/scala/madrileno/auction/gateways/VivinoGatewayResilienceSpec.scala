package madrileno.auction.gateways

import cats.effect.IO
import cats.effect.kernel.Ref
import cats.effect.testing.scalatest.AsyncIOSpec
import io.chrisdavenport.circuit.CircuitBreaker
import io.opentelemetry.api.OpenTelemetry
import madrileno.auction.domain.{Vintage, WineName}
import madrileno.support.TestCacheRuntime
import madrileno.utils.observability.TelemetryContext
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import org.typelevel.otel4s.metrics.Meter
import org.typelevel.otel4s.trace.Tracer
import sttp.capabilities.fs2.Fs2Streams
import sttp.client4.WebSocketStreamBackend
import sttp.client4.impl.cats.CatsMonadAsyncError
import sttp.client4.testing.{ResponseStub, WebSocketStreamBackendStub}

import java.io.IOException
import scala.concurrent.duration.*

class VivinoGatewayResilienceSpec extends AsyncWordSpec with AsyncIOSpec with Matchers {

  given Tracer[IO]       = Tracer.noop[IO]
  given Meter[IO]        = Meter.noop[IO]
  given TelemetryContext = TelemetryContext(Meter.noop[IO], Tracer.noop[IO], OpenTelemetry.noop())

  private val okBody =
    """{"hits":[{"name":"Chateau Margaux","winery":null,"statistics":null,"vintages":[{"year":"2020","statistics":{"ratings_count":1000,"ratings_average":4.5,"status":"Normal"}}]}]}"""

  private val wine: WineName           = WineName("Chateau Margaux")
  private val vintage: Option[Vintage] = Some(Vintage(2020))

  private def backendFromBehavior(behavior: IO[String]): WebSocketStreamBackend[IO, Fs2Streams[IO]] =
    WebSocketStreamBackendStub[IO, Fs2Streams[IO]](new CatsMonadAsyncError[IO]()).whenAnyRequest
      .thenRespondF(_ => behavior.map(body => ResponseStub.adjust(body)))

  private def newGateway(http: WebSocketStreamBackend[IO, Fs2Streams[IO]], cb: CircuitBreaker[IO]): VivinoGatewayLive =
    new VivinoGatewayLive(http, TestCacheRuntime.unbounded, IO.pure(cb))

  "VivinoGatewayLive resilience" should {

    "retry transient failures and eventually succeed" in {
      for {
        callCount <- Ref.of[IO, Int](0)
        backend = backendFromBehavior(callCount.modify { n =>
                    val next = n + 1
                    (next, if (next <= 2) IO.raiseError[String](new IOException("simulated transient")) else IO.pure(okBody))
                  }.flatten)
        cb     <- CircuitBreaker.of[IO](maxFailures = 5, resetTimeout = 1.minute)
        result <- newGateway(backend, cb).findRating(wine, vintage)
        n      <- callCount.get
      } yield {
        result.map(_.rating.unwrap) shouldBe Some(BigDecimal(4.5))
        n shouldBe 3
      }
    }

    "give up and return None after retries are exhausted" in {
      for {
        callCount <- Ref.of[IO, Int](0)
        backend = backendFromBehavior(callCount.update(_ + 1) >> IO.raiseError[String](new IOException("dead")))
        cb     <- CircuitBreaker.of[IO](maxFailures = 99, resetTimeout = 1.minute)
        result <- newGateway(backend, cb).findRating(wine, vintage)
        n      <- callCount.get
      } yield {
        result shouldBe None
        n shouldBe 3 // 1 initial + 2 retries
      }
    }

    "open the breaker after sustained failures and stop calling the backend" in {
      for {
        callCount <- Ref.of[IO, Int](0)
        backend = backendFromBehavior(callCount.update(_ + 1) >> IO.raiseError[String](new IOException("dead")))
        cb <- CircuitBreaker.of[IO](maxFailures = 2, resetTimeout = 1.minute)
        gateway = newGateway(backend, cb)
        // 3 failing calls is enough to open a maxFailures=2 breaker regardless of
        // open-at-vs-open-after-max semantics; each call exhausts retries first
        _              <- gateway.findRating(WineName("A"), None)
        _              <- gateway.findRating(WineName("B"), None)
        _              <- gateway.findRating(WineName("C"), None)
        callsAfterTrip <- callCount.get
        // breaker is open by now; the probe should not hit the backend
        _               <- gateway.findRating(WineName("Z"), None)
        callsAfterProbe <- callCount.get
      } yield {
        callsAfterProbe shouldBe callsAfterTrip
      }
    }
  }
}
