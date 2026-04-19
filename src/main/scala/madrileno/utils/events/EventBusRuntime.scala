package madrileno.utils.events

import cats.effect.IO
import fs2.Stream
import fs2.concurrent.Topic
import io.circe.Codec

import java.util.concurrent.atomic.AtomicReference

trait EventBusRuntime {
  def topic[E: Codec](name: String): EventBus[E]
}

object EventBusRuntime {

  def local: EventBusRuntime = new EventBusRuntime {
    override def topic[E: Codec](name: String): EventBus[E] = new LocalEventBus[E]
  }

  private class LocalEventBus[E] extends EventBus[E] {
    private val topicRef: AtomicReference[Option[Topic[IO, E]]] = new AtomicReference(None)

    private val underlying: IO[Topic[IO, E]] =
      IO.delay(topicRef.get()).flatMap {
        case Some(t) => IO.pure(t)
        case None =>
          Topic[IO, E].flatMap { fresh =>
            IO.delay {
              if (topicRef.compareAndSet(None, Some(fresh))) fresh
              else topicRef.get().get
            }
          }
      }

    override def publish(event: E): IO[Unit] =
      underlying.flatMap(_.publish1(event).void)

    override def subscribe: Stream[IO, E] =
      Stream.eval(underlying).flatMap(_.subscribe(maxQueued = 64))
  }
}
