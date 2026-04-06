package madrileno.utils.task

import cats.effect.testing.scalatest.AsyncIOSpec
import cats.effect.{IO, Ref}
import madrileno.support.TestTransactor
import madrileno.utils.observability.TelemetryContext
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import org.typelevel.otel4s.metrics.Meter
import org.typelevel.otel4s.trace.Tracer

import scala.concurrent.duration.*

class SchedulerIntegrationSpec extends AsyncWordSpec with AsyncIOSpec with Matchers with TestTransactor {

  given Meter[IO]        = Meter.noop[IO]
  given TelemetryContext = TelemetryContext(Meter.noop[IO], Tracer.noop[IO], io.opentelemetry.api.OpenTelemetry.noop())

  private val config = SchedulerConfig(pollingInterval = 100.millis, retryBaseDelay = 200.millis, maxRetries = Some(2))

  private def waitFor(
    ref: Ref[IO, Int],
    target: Int,
    timeout: FiniteDuration = 5.seconds
  ): IO[Int] = {
    def poll(remaining: FiniteDuration): IO[Int] =
      ref.get.flatMap { value =>
        if (value >= target) IO.pure(value)
        else if (remaining <= Duration.Zero) IO.raiseError(new RuntimeException(s"Timed out: got $value, wanted >= $target"))
        else IO.sleep(50.millis) *> poll(remaining - 50.millis)
      }
    poll(timeout)
  }

  "Scheduler" should {
    "execute one-time, recurring, retry, payload, custom, and transactional tasks" in {
      for {
        onceCounter      <- Ref.of[IO, Int](0)
        recurringCounter <- Ref.of[IO, Int](0)
        retryCounter     <- Ref.of[IO, Int](0)
        payloadRef       <- Ref.of[IO, Int](0)
        customCounter    <- Ref.of[IO, Int](0)
        txCounter        <- Ref.of[IO, Int](0)

        onceDescriptor = TaskDescriptor[Unit]("test-once")
        onceTask       = Task.oneTime(onceDescriptor) { _ => onceCounter.update(_ + 1) }

        recurringTask = Task.recurring("test-recurring", Schedule.RecurringWithFixedDelay(200.millis)) { _ =>
                          recurringCounter.update(_ + 1)
                        }

        retryDescriptor = TaskDescriptor[Unit]("test-retry")
        retryTask = Task.oneTime(retryDescriptor) { _ =>
                      retryCounter.update(_ + 1) *> IO.raiseError(new RuntimeException("Intentional failure"))
                    }

        payloadTask = Task.recurring[Int]("test-payload", Schedule.RecurringWithFixedDelay(200.millis), payload = 0) { t =>
                        val next = t.payload + 1
                        payloadRef.set(next).as(next)
                      }

        customDescriptor = TaskDescriptor[String]("test-custom")
        customTask = Task.custom(customDescriptor) { _ =>
                       customCounter.updateAndGet(_ + 1).flatMap { count =>
                         if (count < 3)
                           IO.realTimeInstant.map(now => Schedule.NextAt(now.plusMillis(100), s"iteration-$count"))
                         else IO.unit
                       }
                     }

        txDescriptor = TaskDescriptor[Unit]("test-tx")
        txTask       = Task.oneTime(txDescriptor) { _ => txCounter.update(_ + 1) }

        scheduler = Scheduler(transactor, config)
        client    = scheduler.client

        _ <-
          scheduler
            .run(recurringTasks = List(recurringTask, payloadTask), oneTimeTasks = List(onceTask, retryTask, txTask), customTasks = List(customTask))
            .use { _ =>
              for {
                _ <- client.schedule(onceTask.instance("exec-once", ()))
                _ <- client.schedule(retryTask.instance("exec-retry", ()))
                _ <- client.schedule(customTask.instance("exec-custom", "initial", java.time.Instant.now()))
                _ <- transactor.inTransaction(client.scheduleTransactionally(txTask.instance("exec-tx", ())))

                onceResult      <- waitFor(onceCounter, 1)
                recurringResult <- waitFor(recurringCounter, 3)
                retryResult     <- waitFor(retryCounter, 3, timeout = 10.seconds)
                payloadResult   <- waitFor(payloadRef, 3)
                customResult    <- waitFor(customCounter, 3, timeout = 10.seconds)
                txResult        <- waitFor(txCounter, 1)
              } yield (onceResult, recurringResult, retryResult, payloadResult, customResult, txResult)
            }
      } yield {
        onceCounter.get.unsafeRunSync() shouldBe 1
        recurringCounter.get.unsafeRunSync() should be >= 3
        retryCounter.get.unsafeRunSync() shouldBe 3
        payloadRef.get.unsafeRunSync() should be >= 3
        customCounter.get.unsafeRunSync() shouldBe 3
        txCounter.get.unsafeRunSync() shouldBe 1
      }
    }
  }
}
