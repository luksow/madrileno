package madrileno.utils.events

import cats.effect.IO
import fs2.Stream

trait EventBus[E] {
  def publish(event: E): IO[Unit]
  def subscribe: Stream[IO, E]
}
