package madrileno.utils.cache

import cats.effect.kernel.Outcome
import cats.effect.std.Supervisor
import cats.effect.{Deferred, IO, Ref, Resource}
import cats.syntax.all.*
import com.github.blemale.scaffeine.{Cache, Scaffeine}
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.slf4j.Slf4jLogger
import org.typelevel.otel4s.metrics.Meter

import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicLong
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

    val inFlight: Ref[IO, Map[K, Deferred[IO, Either[Throwable, V]]]] =
      Ref.unsafe(Map.empty)

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
            inFlight.modify { state =>
              state.get(key) match {
                case Some(existing) =>
                  state -> poll(existing.get.flatMap(IO.fromEither))

                case None =>
                  val action =
                    poll(runLoad(key, load, fresh)).guarantee(inFlight.update(_ - key))
                  state.updated(key, fresh) -> action
              }
            }.flatten
          }
        }

      // Ordering invariant: signal.complete fires before storage.put (via
      // publishIfStillOwned). A racing caller arriving between those two steps still finds
      // the in-flight entry (cleanup runs last via `guarantee`) and waits on the Deferred —
      // no value is lost.
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

      // Only write to storage if this load is still the registered owner of the in-flight
      // slot. A concurrent `invalidate` or `put` clears the slot first; this check ensures
      // a stale loader can't resurrect cleared data or overwrite a newer explicit put.
      // (A narrow race remains between the check and the actual put, but the common case
      // — invalidate/put issued while a load is outstanding — is handled correctly.)
      private def publishIfStillOwned(
        key: K,
        value: V,
        signal: Deferred[IO, Either[Throwable, V]]
      ): IO[Unit] =
        inFlight.get.flatMap { state =>
          state.get(key) match {
            case Some(d) if d eq signal =>
              IO.delay(storage.put(key, value)).attempt.flatMap {
                case Right(_) => IO.unit
                case Left(t)  => logger.warn(t)(s"IOCache: storage.put failed for key $key")
              }
            case _ =>
              IO.unit
          }
        }

      override def get(key: K): IO[Option[V]] =
        IO.delay(storage.getIfPresent(key))

      // Explicit put supersedes any outstanding load for the same key — clear the
      // in-flight slot so a stale loader's eventual publishIfStillOwned is a no-op.
      override def put(key: K, value: V): IO[Unit] =
        inFlight.update(_ - key) *> IO.delay(storage.put(key, value))

      // Same reason as `put`: remove the in-flight slot first so stale loaders skip their
      // publish step when they complete.
      override def invalidate(key: K): IO[Unit] =
        inFlight.update(_ - key) *> IO.delay(storage.invalidate(key))

      override def invalidateAll: IO[Unit] =
        inFlight.set(Map.empty) *> IO.delay(storage.invalidateAll())

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
