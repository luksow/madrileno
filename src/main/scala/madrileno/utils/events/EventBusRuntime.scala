package madrileno.utils.events

import cats.effect.std.Supervisor
import cats.effect.{Deferred, IO, Ref}
import fs2.Stream
import fs2.concurrent.Topic
import madrileno.utils.db.transactor.Transactor
import madrileno.utils.observability.{LoggingSupport, TelemetryContext}
import skunk.data.Identifier

import scala.concurrent.duration.*

/** Pluggable event-bus factory.
  *
  * `local` keeps fan-out in-process via fs2 Topic — fine for tests and single-instance dev. NOTE: each call to `topic(name, _)` returns an
  * **independent** bus; the `name` is not used for sharing. Callers that need a shared bus must wire it once and pass the reference around.
  *
  * `postgres` shares state across instances over LISTEN/NOTIFY on the named channel — two `topic("x", _)` calls (in the same JVM or another instance)
  * resolve to the same logical bus.
  */
trait EventBusRuntime {
  def topic[E: EventCodec](name: String, maxQueued: Int): EventBus[E]
}

object EventBusRuntime {

  def local: EventBusRuntime = new EventBusRuntime {
    override def topic[E: EventCodec](name: String, maxQueued: Int): EventBus[E] = new LocalEventBus[E](maxQueued)
  }

  def postgres(transactor: Transactor)(using Supervisor[IO], TelemetryContext): EventBusRuntime = new EventBusRuntime {
    override def topic[E: EventCodec](name: String, maxQueued: Int): EventBus[E] = new PostgresEventBus[E](transactor, name, maxQueued)
  }

  // One-shot memoization of `init`. Concurrent callers share a single Deferred so
  // `init` runs at most once even under contention — no leaked Topics or supervised
  // listener fibers if multiple subscribers race during startup.
  private def memoize[A](init: IO[A]): IO[A] = {
    val ref   = Ref.unsafe[IO, Option[Deferred[IO, Either[Throwable, A]]]](None)
    val fresh = Deferred.unsafe[IO, Either[Throwable, A]]
    ref.modify {
      case Some(d) => (Some(d), d.get.rethrow)
      case None    => (Some(fresh), init.attempt.flatTap(fresh.complete(_).void).rethrow)
    }.flatten
  }

  private class LocalEventBus[E](maxQueued: Int) extends EventBus[E] {
    private val topic: IO[Topic[IO, E]] = memoize(Topic[IO, E])

    override def publish(event: E): IO[Unit] = topic.flatMap(_.publish1(event)).void
    override def subscribe: Stream[IO, E]    = Stream.eval(topic).flatMap(_.subscribe(maxQueued))
  }

  private class PostgresEventBus[E: EventCodec](
    transactor: Transactor,
    name: String,
    maxQueued: Int
  )(using
    supervisor: Supervisor[IO],
    tc: TelemetryContext)
      extends EventBus[E]
      with LoggingSupport {

    private val identifier = Identifier.fromString(name).fold(msg => sys.error(s"Invalid channel name '$name': $msg"), identity)
    private val codec      = EventCodec[E]

    private val topic: IO[Topic[IO, E]] =
      memoize(Topic[IO, E].flatTap(t => supervisor.supervise(listenLoop(t).foreverM)))

    override def publish(event: E): IO[Unit] =
      transactor.notify(identifier, codec.encode(event))

    override def subscribe: Stream[IO, E] =
      Stream.eval(topic).flatMap(_.subscribe(maxQueued))

    private def listenLoop(sink: Topic[IO, E]): IO[Unit] =
      transactor
        .listen(identifier, maxQueued)
        .evalMap { notification =>
          codec.decode(notification.value) match {
            case Right(event) => sink.publish1(event).void
            case Left(err)    => logger.warn(err)(s"Failed to decode notification on $name: ${notification.value}")
          }
        }
        .compile
        .drain
        .handleErrorWith(t => logger.warn(t)(s"LISTEN on $name dropped — reconnecting after 1s") *> IO.sleep(1.second))
  }
}
