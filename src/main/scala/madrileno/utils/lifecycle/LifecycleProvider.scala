package madrileno.utils.lifecycle

import cats.effect.{IO, Resource}

trait LifecycleProvider {
  def lifecycles: List[Resource[IO, Unit]] = Nil
}
