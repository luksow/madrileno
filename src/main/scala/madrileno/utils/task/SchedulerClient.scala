package madrileno.utils.task

import cats.effect.IO
import madrileno.utils.db.transactor.{DB, DBInTransaction, Transactor}

class SchedulerClient private[task] (
  repository: ClientSchedulerRepository,
  transactor: Transactor
) {
  def schedule[A](task: Task[A]): IO[Boolean] =
    transactor.inSession(repository.save(task))

  def scheduleInSession[A](task: Task[A]): DB[Boolean] =
    repository.save(task)

  def scheduleTransactionally[A](task: Task[A]): DBInTransaction[Boolean] =
    repository.save(task)
}
