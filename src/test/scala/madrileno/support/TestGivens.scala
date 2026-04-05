package madrileno.support

import cats.effect.std.UUIDGen
import cats.effect.{Clock, IO}

import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.{AtomicInteger, AtomicReference}
import scala.concurrent.duration.FiniteDuration

/** A controllable Clock for tests — returns a fixed instant that can be advanced. For scheduler timing tests that need full IO time control, use
  * cats-effect TestControl instead.
  */
class TestClock(initial: Instant = Instant.now()) extends Clock[IO] {
  private val current = new AtomicReference[Instant](initial)

  def advance(millis: Long): Unit = { current.updateAndGet(_.plusMillis(millis)): Unit }
  def set(instant: Instant): Unit = current.set(instant)
  def now: Instant                = current.get()

  override def realTime: IO[FiniteDuration] =
    IO.pure(FiniteDuration(current.get().toEpochMilli, TimeUnit.MILLISECONDS))

  override def monotonic: IO[FiniteDuration] = realTime

  override def applicative: cats.Applicative[IO] = cats.effect.IO.asyncForIO
}

/** A predictable UUIDGen for tests — returns UUIDs from a sequence, then generates deterministic ones. */
class TestUUIDGen(uuids: UUID*) extends UUIDGen[IO] {
  private val index = new AtomicInteger(0)

  override def randomUUID: IO[UUID] = IO {
    val i = index.getAndIncrement()
    if (i < uuids.length) uuids(i)
    else UUID.nameUUIDFromBytes(s"test-uuid-$i".getBytes)
  }
}

object TestGivens {
  def fixedClock(at: Instant = Instant.now()): TestClock = new TestClock(at)
  def deterministicUUIDs(uuids: UUID*): TestUUIDGen      = new TestUUIDGen(uuids*)
}
