package madrileno.utils.events

import cats.effect.IO
import cats.effect.std.Supervisor
import fs2.Stream
import fs2.concurrent.Topic
import io.circe.Codec
import io.circe.parser.decode
import io.circe.syntax.*
import madrileno.utils.db.transactor.Transactor
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.slf4j.Slf4jLogger
import skunk.data.Identifier

import java.util.concurrent.atomic.AtomicReference

trait EventBusRuntime {
  def topic[E: Codec](name: String): EventBus[E]
}

object EventBusRuntime {

  /** In-process pub/sub backed by fs2.Topic. Events reach only subscribers attached to the same process. */
  def local: EventBusRuntime = new EventBusRuntime {
    override def topic[E: Codec](name: String): EventBus[E] = new LocalEventBus[E]
  }

  /** Multi-instance pub/sub backed by Postgres LISTEN/NOTIFY. Every instance connected to the same database sees every publish, which is what the
    * front-page live ticker needs across a replicated deployment.
    *
    * Trade-offs: NOTIFY payloads are capped at 8 KB; messages are transient (no persistence/replay); a subscriber that disconnects misses events
    * while offline.
    */
  def postgres(transactor: Transactor)(using Supervisor[IO]): EventBusRuntime = new EventBusRuntime {
    override def topic[E: Codec](name: String): EventBus[E] = new PostgresEventBus[E](transactor, name)
  }

  private val logger: Logger[IO] = Slf4jLogger.getLogger[IO]

  private class LocalEventBus[E] extends EventBus[E] {
    // Bind the Ref so repeated get/put see the same cell.
    private val ref: AtomicReference[Option[Topic[IO, E]]] = new AtomicReference(None)

    private val underlying: IO[Topic[IO, E]] =
      IO.delay(ref.get()).flatMap {
        case Some(t) => IO.pure(t)
        case None =>
          Topic[IO, E].flatMap { fresh =>
            IO.delay {
              if (ref.compareAndSet(None, Some(fresh))) fresh
              else ref.get().get
            }
          }
      }

    override def publish(event: E): IO[Unit] = underlying.flatMap(_.publish1(event).void)
    override def subscribe: Stream[IO, E]    = Stream.eval(underlying).flatMap(_.subscribe(maxQueued = 64))
  }

  private class PostgresEventBus[E: Codec](transactor: Transactor, name: String)(using supervisor: Supervisor[IO]) extends EventBus[E] {

    private val identifier = Identifier.fromString(name).fold(msg => sys.error(s"Invalid channel name '$name': $msg"), identity)

    // Local fan-out: the single LISTEN fiber pushes decoded events here so any number of in-process
    // subscribers share one Postgres subscription.
    private val localRef: AtomicReference[Option[Topic[IO, E]]] = new AtomicReference(None)

    private val localTopic: IO[Topic[IO, E]] =
      IO.delay(localRef.get()).flatMap {
        case Some(t) => IO.pure(t)
        case None =>
          Topic[IO, E].flatMap { fresh =>
            IO.delay {
              if (localRef.compareAndSet(None, Some(fresh))) fresh
              else localRef.get().get
            }
          }
      }

    private val listenerStarted = new AtomicReference(false)

    override def publish(event: E): IO[Unit] =
      transactor.inSession {
        summon[skunk.Session[IO]].channel(identifier).notify(event.asJson.noSpaces)
      }

    override def subscribe: Stream[IO, E] =
      Stream.eval(ensureListener) >> Stream.eval(localTopic).flatMap(_.subscribe(maxQueued = 64))

    private def ensureListener: IO[Unit] =
      IO.delay(listenerStarted.compareAndSet(false, true)).flatMap {
        case false => IO.unit
        case true =>
          supervisor.supervise {
            localTopic.flatMap { sink =>
              transactor.sessions
                .use { session =>
                  session
                    .channel(identifier)
                    .listen(maxQueued = 64)
                    .evalMap { notification =>
                      decode[E](notification.value) match {
                        case Right(event) => sink.publish1(event).void
                        case Left(err)    => logger.warn(err)(s"Failed to decode notification on channel $name: ${notification.value}")
                      }
                    }
                    .compile
                    .drain
                }
                .handleErrorWith(t => logger.error(t)(s"Postgres LISTEN fiber for $name crashed"))
            }
          }.void
      }
  }
}
