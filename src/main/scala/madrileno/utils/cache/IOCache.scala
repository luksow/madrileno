package madrileno.utils.cache

import cats.effect.kernel.Outcome
import cats.effect.{Deferred, IO, Ref}
import com.github.blemale.scaffeine.{Cache, Scaffeine}

import java.util.concurrent.CancellationException
import scala.concurrent.duration.FiniteDuration

trait IOCache[K, V] {
  def getOrLoad(key: K)(load: IO[V]): IO[V]
  def get(key: K): IO[Option[V]]
  def put(key: K, value: V): IO[Unit]
  def invalidate(key: K): IO[Unit]
}

object IOCache {

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
        .build[K, V]()

    val inFlight: Ref[IO, Map[K, Deferred[IO, Either[Throwable, V]]]] =
      Ref.unsafe(Map.empty)

    new IOCache[K, V] {

      override def getOrLoad(key: K)(load: IO[V]): IO[V] =
        get(key).flatMap {
          case Some(v) => IO.pure(v)
          case None    => dedupLoad(key, load)
        }

      private def dedupLoad(key: K, load: IO[V]): IO[V] =
        Deferred[IO, Either[Throwable, V]].flatMap { fresh =>
          inFlight.modify { state =>
            state.get(key) match {
              case Some(existing) =>
                state -> existing.get.flatMap(IO.fromEither)

              case None =>
                val action =
                  runLoad(key, load, fresh).guarantee(inFlight.update(_ - key))
                state.updated(key, fresh) -> action
            }
          }.flatten
        }

      // Ordering invariant: signal.complete fires before storage.put.
      // A racing caller arriving between those two steps still finds the in-flight entry
      // (cleanup runs last via `guarantee`) and waits on the Deferred — no value is lost.
      private def runLoad(
        key: K,
        load: IO[V],
        signal: Deferred[IO, Either[Throwable, V]]
      ): IO[V] =
        load.guaranteeCase {
          case Outcome.Succeeded(fa) =>
            fa.attempt.flatMap {
              case Right(v) =>
                signal.complete(Right(v)).attempt.void *>
                  IO.delay(storage.put(key, v)).attempt.void

              case Left(e) =>
                signal.complete(Left(e)).attempt.void
            }

          case Outcome.Errored(e) =>
            signal.complete(Left(e)).attempt.void

          case Outcome.Canceled() =>
            signal
              .complete(Left(new CancellationException("IOCache loader cancelled")))
              .attempt
              .void
        }

      override def get(key: K): IO[Option[V]] =
        IO.delay(storage.getIfPresent(key))

      override def put(key: K, value: V): IO[Unit] =
        IO.delay(storage.put(key, value))

      override def invalidate(key: K): IO[Unit] =
        IO.delay(storage.invalidate(key))
    }
  }
}
