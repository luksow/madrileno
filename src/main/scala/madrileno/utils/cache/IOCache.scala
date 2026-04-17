package madrileno.utils.cache

import cats.effect.kernel.Outcome
import cats.effect.std.Supervisor
import cats.effect.{Deferred, IO, Resource}
import cats.syntax.all.*
import com.github.blemale.scaffeine.{Cache, Scaffeine}
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.slf4j.Slf4jLogger
import org.typelevel.otel4s.metrics.Meter

import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.{AtomicLong, AtomicReference}
import scala.annotation.tailrec
import scala.concurrent.duration.FiniteDuration

trait IOCache[K, V] {
  def getOrLoad(key: K)(load: IO[V]): IO[V]
  def get(key: K): IO[Option[V]]
  def put(key: K, value: V): IO[Unit]
  def invalidate(key: K): IO[Unit]
  def invalidateAll: IO[Unit]
  def estimatedSize: IO[Long]
  def stats: IO[IOCache.CacheStats]
}

object IOCache {

  private val logger: Logger[IO] = Slf4jLogger.getLogger[IO]

  /** Combines Caffeine's native stats (hits, misses, evictions) with load counters we track ourselves — Caffeine doesn't see our IO loader. */
  final case class CacheStats(
    hitCount: Long,
    missCount: Long,
    requestCount: Long,
    hitRate: Double,
    missRate: Double,
    evictionCount: Long,
    loadSuccessCount: Long,
    loadFailureCount: Long,
    loadFailureRate: Double)

  def apply[K, V](expireAfter: V => FiniteDuration, maximumSize: Long): IOCache[K, V] = {
    val storage: Cache[K, V] =
      Scaffeine()
        .maximumSize(maximumSize)
        .expireAfter[K, V](
          create = (_, value) => expireAfter(value),
          update = (
            _,
            value,
            _
          ) => expireAfter(value),
          read = (
            _,
            _,
            currentDuration
          ) => currentDuration
        )
        .recordStats()
        .build[K, V]()

    // In-flight loads, deduplicated per key. Kept as a plain AtomicReference rather than a
    // Ref[IO, _] so that `asMap.compute(...)` (which runs inside Caffeine's per-key lock)
    // can synchronously read and update it — that's what makes publishIfStillOwned,
    // invalidate, and put atomic with respect to each other.
    val inFlight: AtomicReference[Map[K, Deferred[IO, Either[Throwable, V]]]] =
      new AtomicReference(Map.empty)

    val underlying = storage.underlying

    // Caffeine never sees our IO loader, so we count success/failure ourselves.
    val loadSuccessCount = new AtomicLong(0L)
    val loadFailureCount = new AtomicLong(0L)

    new IOCache[K, V] {

      override def getOrLoad(key: K)(load: IO[V]): IO[V] =
        get(key).flatMap {
          case Some(v) => IO.pure(v)
          case None    => dedupLoad(key, load)
        }

      // `IO.uncancelable` seals the transition from "Deferred registered in inFlight" to
      // "action with cleanup finalizer attached is being run." Without it, a cancellation
      // between the two would orphan the Deferred: never completed, never removed, with
      // later callers blocking forever. The `poll(...)` holes re-enable cancellation for
      // the actual wait/load so a Ctrl-C still works.
      private def dedupLoad(key: K, load: IO[V]): IO[V] =
        IO.uncancelable { poll =>
          Deferred[IO, Either[Throwable, V]].flatMap { fresh =>
            IO.delay(registerOrWait(key, fresh)).flatMap {
              case Left(existing) =>
                poll(existing.get.flatMap(IO.fromEither))
              case Right(_) =>
                // The finalizer must be identity-safe: if invalidate/put cleared the slot
                // and a new loader took over, we must not evict the new loader's Deferred.
                poll(runLoad(key, load, fresh))
                  .guarantee(IO.delay(dropIfOwned(key, fresh)).void)
            }
          }
        }

      // Atomic "check, register if absent" on the inFlight AtomicReference. Returns Left if
      // another loader already owns the slot, Right(()) if we successfully registered.
      @tailrec private def registerOrWait(key: K, fresh: Deferred[IO, Either[Throwable, V]]): Either[Deferred[IO, Either[Throwable, V]], Unit] = {
        val cur = inFlight.get()
        cur.get(key) match {
          case Some(existing) => Left(existing)
          case None =>
            if (inFlight.compareAndSet(cur, cur.updated(key, fresh))) Right(())
            else registerOrWait(key, fresh)
        }
      }

      private def dropIfOwned(key: K, owner: Deferred[IO, Either[Throwable, V]]): Unit =
        inFlight.updateAndGet(m => if (m.get(key).exists(_ eq owner)) m - key else m)

      private def runLoad(
        key: K,
        load: IO[V],
        signal: Deferred[IO, Either[Throwable, V]]
      ): IO[V] =
        load.guaranteeCase {
          case Outcome.Succeeded(fa) =>
            fa.attempt.flatMap {
              case Right(v) =>
                IO.delay(loadSuccessCount.incrementAndGet()).void *>
                  signal.complete(Right(v)).attempt.void *>
                  publishIfStillOwned(key, v, signal)

              case Left(e) =>
                IO.delay(loadFailureCount.incrementAndGet()).void *>
                  signal.complete(Left(e)).attempt.void
            }

          case Outcome.Errored(e) =>
            IO.delay(loadFailureCount.incrementAndGet()).void *>
              signal.complete(Left(e)).attempt.void

          case Outcome.Canceled() =>
            signal
              .complete(Left(new CancellationException("IOCache loader cancelled")))
              .attempt
              .void
        }

      // Atomic publish: inside Caffeine's per-key lock, read inFlight and either write the
      // value (if we still own the slot) or leave the existing entry untouched. Because
      // `put`, `invalidate`, and this method all coordinate through `asMap.compute` for the
      // same key, they serialise with each other — a concurrent invalidate arriving during
      // our publish is either fully before or fully after. Any storage.put failure is
      // logged rather than swallowed.
      private def publishIfStillOwned(
        key: K,
        value: V,
        signal: Deferred[IO, Either[Throwable, V]]
      ): IO[Unit] =
        IO.delay {
          underlying.asMap.compute(
            key,
            (_: K, existing: V) =>
              if (inFlight.get().get(key).exists(_ eq signal)) {
                inFlight.updateAndGet(m => if (m.get(key).exists(_ eq signal)) m - key else m)
                value
              } else {
                existing
              }
          )
          ()
        }.handleErrorWith(t => logger.warn(t)(s"IOCache: publish failed for key $key"))

      override def get(key: K): IO[Option[V]] =
        IO.delay(Option(underlying.getIfPresent(key)))

      // Always update inFlight BEFORE the Caffeine write. Caffeine's put/invalidate and the
      // `asMap.compute` in publishIfStillOwned all serialise through the same per-key lock,
      // so by the time a concurrent publish acquires the lock the inFlight slot is already
      // cleared and its ownership check short-circuits.
      override def put(key: K, value: V): IO[Unit] =
        IO.delay {
          inFlight.updateAndGet(_ - key)
          underlying.put(key, value)
        }

      override def invalidate(key: K): IO[Unit] =
        IO.delay {
          inFlight.updateAndGet(_ - key)
          underlying.invalidate(key)
        }

      // invalidateAll has coarser atomicity than `invalidate` — Caffeine's invalidateAll
      // doesn't take per-key locks in bulk. Clearing inFlight first ensures any currently
      // in-flight loader's publishIfStillOwned finds no ownership and skips its put.
      override def invalidateAll: IO[Unit] =
        IO.delay {
          inFlight.set(Map.empty)
          underlying.invalidateAll()
        }

      override def estimatedSize: IO[Long] =
        IO.delay(storage.estimatedSize())

      override def stats: IO[CacheStats] =
        IO.delay {
          val s           = storage.stats()
          val loadSuccess = loadSuccessCount.get()
          val loadFailure = loadFailureCount.get()
          val loadTotal   = loadSuccess + loadFailure
          val hits        = s.hitCount()
          val misses      = s.missCount()
          val requests    = hits + misses
          CacheStats(
            hitCount = hits,
            missCount = misses,
            requestCount = requests,
            hitRate = if (requests == 0L) 1.0 else hits.toDouble / requests,
            missRate = if (requests == 0L) 0.0 else misses.toDouble / requests,
            evictionCount = s.evictionCount(),
            loadSuccessCount = loadSuccess,
            loadFailureCount = loadFailure,
            loadFailureRate = if (loadTotal == 0L) 0.0 else loadFailure.toDouble / loadTotal
          )
        }
    }
  }

  /** Registers otel4s observable counters/gauges for the cache's stats and size. The registration stays alive for the lifetime of the ambient
    * Supervisor[IO].
    */
  def registerMetrics[K, V](name: String, cache: IOCache[K, V])(using supervisor: Supervisor[IO], meter: Meter[IO]): IO[Unit] =
    supervisor.supervise(metricResource(name, cache).useForever).void

  private def metricResource[K, V](name: String, cache: IOCache[K, V])(using meter: Meter[IO]): Resource[IO, Unit] = {
    def counter(suffix: String, extract: CacheStats => Long): Resource[IO, Unit] =
      meter
        .observableCounter[Long](s"$name.$suffix")
        .createWithCallback(cb => cache.stats.flatMap(s => cb.record(extract(s))))
        .void

    val sizeGauge: Resource[IO, Unit] =
      meter
        .observableGauge[Long](s"$name.size")
        .createWithCallback(cb => cache.estimatedSize.flatMap(cb.record(_)))
        .void

    for {
      _ <- counter("hits", _.hitCount)
      _ <- counter("misses", _.missCount)
      _ <- counter("evictions", _.evictionCount)
      _ <- counter("load.success", _.loadSuccessCount)
      _ <- counter("load.failure", _.loadFailureCount)
      _ <- sizeGauge
    } yield ()
  }
}
