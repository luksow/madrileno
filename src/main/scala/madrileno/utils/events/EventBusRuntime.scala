package madrileno.utils.events

import cats.effect.std.Supervisor
import cats.effect.{IO, Ref, Resource}
import fs2.Stream
import fs2.concurrent.Topic
import io.circe.Codec
import io.circe.parser.decode
import io.circe.syntax.*
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.slf4j.Slf4jLogger
import skunk.Session
import skunk.data.Identifier

import scala.concurrent.duration.*

trait EventBusRuntime {
  def topic[E: Codec](name: String): EventBus[E]
}

object EventBusRuntime {

  def local: EventBusRuntime = new EventBusRuntime {
    override def topic[E: Codec](name: String): EventBus[E] = new LocalEventBus[E]
  }

  def postgres(sessions: Resource[IO, Session[IO]])(using Supervisor[IO]): EventBusRuntime = new EventBusRuntime {
    override def topic[E: Codec](name: String): EventBus[E] = new PostgresEventBus[E](sessions, name)
  }

  private val logger: Logger[IO] = Slf4jLogger.getLogger[IO]

  private class LocalEventBus[E] extends EventBus[E] {
    private val topicRef: Ref[IO, Option[Topic[IO, E]]] = Ref.unsafe(None)

    private val underlying: IO[Topic[IO, E]] =
      topicRef.get.flatMap {
        case Some(t) => IO.pure(t)
        case None    => Topic[IO, E].flatMap(fresh => topicRef.modify(_.fold((Some(fresh), fresh))(t => (Some(t), t))))
      }

    override def publish(event: E): IO[Unit] = underlying.flatMap(_.publish1(event).void)
    override def subscribe: Stream[IO, E]    = Stream.eval(underlying).flatMap(_.subscribe(maxQueued = 64))
  }

  private class PostgresEventBus[E: Codec](sessions: Resource[IO, Session[IO]], name: String)(using supervisor: Supervisor[IO]) extends EventBus[E] {

    private val identifier = Identifier.fromString(name).fold(msg => sys.error(s"Invalid channel name '$name': $msg"), identity)

    private val localRef: Ref[IO, Option[Topic[IO, E]]] = Ref.unsafe(None)
    private val localTopic: IO[Topic[IO, E]] =
      localRef.get.flatMap {
        case Some(t) => IO.pure(t)
        case None    => Topic[IO, E].flatMap(fresh => localRef.modify(_.fold((Some(fresh), fresh))(t => (Some(t), t))))
      }

    private val listenerStarted: Ref[IO, Boolean] = Ref.unsafe(false)

    override def publish(event: E): IO[Unit] =
      sessions.use(_.channel(identifier).notify(event.asJson.noSpaces))

    override def subscribe: Stream[IO, E] =
      Stream.eval(ensureListener) >> Stream.eval(localTopic).flatMap(_.subscribe(maxQueued = 64))

    private def ensureListener: IO[Unit] =
      listenerStarted.getAndSet(true).flatMap {
        case true  => IO.unit
        case false => supervisor.supervise(listenLoop.foreverM).void
      }

    private def listenLoop: IO[Unit] =
      localTopic
        .flatMap { sink =>
          sessions.use { session =>
            session
              .channel(identifier)
              .listen(maxQueued = 64)
              .evalMap { notification =>
                decode[E](notification.value) match {
                  case Right(event) => sink.publish1(event).void
                  case Left(err)    => logger.warn(err)(s"Failed to decode notification on $name: ${notification.value}")
                }
              }
              .compile
              .drain
          }
        }
        .handleErrorWith(t => logger.warn(t)(s"LISTEN on $name dropped — reconnecting after 1s") *> IO.sleep(1.second))
  }
}
