package madrileno.utils.events

import cats.effect.{IO, Resource}
import fs2.Stream

trait EventBus[E] {
  def publish(event: E): IO[Unit]
  def subscribe: Stream[IO, E]
  def subscribeAwait: Resource[IO, Stream[IO, E]]
}
