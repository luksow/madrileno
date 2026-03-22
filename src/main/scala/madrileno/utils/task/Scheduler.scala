package madrileno.utils.task

import cats.effect.std.{Semaphore, Supervisor}
import cats.syntax.all.*
import cats.effect.{Clock, IO, Resource}
import madrileno.utils.db.transactor.{DB, Transactor}
import madrileno.utils.observability.{LoggingSupport, TelemetryContext}
import org.typelevel.otel4s.Attribute
import org.typelevel.otel4s.trace.StatusCode

import java.net.InetAddress

class Scheduler(
  transactor: Transactor,
  config: SchedulerConfig = SchedulerConfig()
)(using
  Clock[IO],
  TelemetryContext)
    extends LoggingSupport {

  private val concurrency         = config.concurrency
  private val pollingInterval     = config.pollingInterval
  private val heartbeatInterval   = config.heartbeatInterval
  private val missedHeartbeatLimit = config.missedHeartbeatLimit
  private val retryBaseDelay      = config.retryBaseDelay
  private val retryBackoffRate    = config.retryBackoffRate
  private val retryMaxDelay       = config.retryMaxDelay
  private val maxRetries          = config.maxRetries

  private val schedulerNameToUse: IO[String] = config.schedulerName match {
    case Some(name) => IO.pure(name)
    case None =>
      IO.blocking {
        InetAddress.getLocalHost.getHostName
      }.recover { case _ =>
        "unknown-host"
      }
  }

  def run(
    recurringTasks: List[Task[?]] = Nil,
    oneTimeTasks: List[OneTimeTask[?]] = Nil,
    customTasks: List[CustomTask[?]] = Nil
  ): Resource[IO, RunningScheduler] = {
    (for {
      schedulerName <- Resource.eval(schedulerNameToUse)
      repository    <- Resource.eval(setup(schedulerName, recurringTasks, oneTimeTasks, customTasks))
      _             <- mainLoop(repository)
    } yield new RunningScheduler(repository, transactor)).onFinalize {
      logger.info("Shutting down scheduler...")
    }
  }

  private def setup(
    schedulerName: String,
    startTasks: List[Task[?]],
    oneTimeTasks: List[OneTimeTask[?]],
    customTasks: List[CustomTask[?]]
  ): IO[SchedulerRepository] = {
    val repository = new SchedulerRepository(schedulerName, startTasks, oneTimeTasks, customTasks)
    logger.info(
      s"Starting scheduler with concurrency=$concurrency, pollingInterval=$pollingInterval, heartbeatInterval=$heartbeatInterval, missedHeartbeatLimit=$missedHeartbeatLimit, " +
        s"retryBaseDelay=$retryBaseDelay, retryBackoffRate=$retryBackoffRate, retryMaxDelay=$retryMaxDelay, maxRetries=$maxRetries, " +
        s"initial tasks=${startTasks.map(_.taskId)}, " +
        s"registered one time tasks=${oneTimeTasks.map(_.descriptor.taskName)}, registered custom tasks=${customTasks.map(_.descriptor.taskName)}"
    ) *>
      transactor
        .inSession {
          startTasks.traverse(repository.registerOnStartup)
        }
        .as(repository)
  }

  private def reservePermits(sem: Semaphore[IO], max: Int): IO[Int] = {
    0.tailRecM { acquired =>
      if (acquired >= max)
        IO.pure(Right(acquired))
      else
        sem.tryAcquire.map {
          case true  => Left(acquired + 1)
          case false => Right(acquired)
        }
    }
  }

  private def heartbeatLoop(repository: SchedulerRepository, task: Task[?]): IO[Unit] = {
    def oneBeat: IO[Unit] =
      transactor
        .inSession {
          repository.updateHeartbeat(task)
        }
        .handleErrorWith { e =>
          logger.error(e)(s"Heartbeat failed for ${task.taskId}")
        }

    (IO.sleep(heartbeatInterval) *> oneBeat).foreverM
  }

  private def withVersionCheck(taskId: String, operation: String)(db: DB[Boolean]): IO[Unit] =
    transactor.inSession(db).flatMap {
      case true  => IO.unit
      case false => logger.warn(s"$taskId: $operation affected 0 rows (version mismatch, likely recovered by dead execution handler)")
    }

  private def onSuccess[A](
    repository: SchedulerRepository,
    task: Task[A],
    result: Unit | A | Schedule.NextAt[A]
  ): IO[Unit] = {
    val taskId = task.taskId

    Clock[IO].realTimeInstant.flatMap { now =>
      task.schedule match {
        case _: Once =>
          logger.info(s"$taskId completed, removing") *>
            withVersionCheck(taskId, "remove")(repository.remove(task))
        case Schedule.RecurringWithFixedRate(every, _) =>
          val anchor    = task.scheduledAt.getOrElse(now)
          val candidate = anchor.plusMillis(every.toMillis)
          val next      = if (candidate.isBefore(now)) now.plusMillis(every.toMillis) else candidate
          val newPayload = result match {
            case ()      => None
            case payload => Some(payload.asInstanceOf[A])
          }
          logger.info(s"$taskId completed, next at $next") *>
            withVersionCheck(taskId, "reschedule")(repository.reschedule(task, next, newPayload))
        case Schedule.RecurringWithFixedDelay(after, _) =>
          val next = now.plusMillis(after.toMillis)
          val newPayload = result match {
            case ()      => None
            case payload => Some(payload.asInstanceOf[A])
          }
          logger.info(s"$taskId completed, next at $next") *>
            withVersionCheck(taskId, "reschedule")(repository.reschedule(task, next, newPayload))
        case Schedule.NextAt(_, _) =>
          result match {
            case nextAt: Schedule.NextAt[A @unchecked] =>
              logger.info(s"$taskId completed, next at ${nextAt.at}") *>
                withVersionCheck(taskId, "reschedule")(repository.reschedule(task, nextAt.at, Some(nextAt.payload)))
            case _ =>
              logger.info(s"$taskId completed (custom, no continuation), removing") *>
                withVersionCheck(taskId, "remove")(repository.remove(task))
          }
      }
    }
  }

  private def onFailure[A](
    repository: SchedulerRepository,
    task: Task[A],
    error: Throwable
  ): IO[Unit] = {
    val taskId     = task.taskId
    val previous   = task.consecutiveFailures.getOrElse(0)
    val failures   = previous + 1
    val shouldDrop = maxRetries.exists(failures > _)

    if (shouldDrop) {
      logger.error(error)(s"$taskId exceeded max retries ($failures), removing") *>
        withVersionCheck(taskId, "remove")(repository.remove(task))
    } else {
      Clock[IO].realTimeInstant.flatMap { now =>
        val backoffMs = math.min((retryBaseDelay.toMillis * math.pow(retryBackoffRate, previous.toDouble)).toLong, retryMaxDelay.toMillis)
        val retryAt   = now.plusMillis(backoffMs)
        logger.error(error)(s"$taskId failed (attempt $failures), retrying at $retryAt") *>
          withVersionCheck(taskId, "markFailure")(repository.markFailure(task, retryAt, failures))
      }
    }
  }

  private def executeAndComplete[A](repository: SchedulerRepository, task: Task[A]): IO[Unit] = {
    val taskId     = task.taskId
    val payload    = task.descriptor.encoder(task.payload).noSpaces
    val attributes = Seq(
      Attribute("task.name", task.descriptor.taskName),
      Attribute("task.instance", task.taskInstance),
      Attribute("task.version", task.version),
      Attribute("task.priority", task.priority.toLong),
      Attribute("task.payload", payload)
    ) ++ task.consecutiveFailures.map(f => Attribute("task.consecutive_failures", f.toLong))

    summon[TelemetryContext].tracer
      .span(s"scheduler.execute $taskId", attributes)
      .use { span =>
        task.execution(task).attempt.flatMap {
          case Right(result) =>
            onSuccess(repository, task, result)
              .handleErrorWith { e =>
                logger.error(e)(s"Failed to complete $taskId after success")
              }
          case Left(e) =>
            span.recordException(e) *>
              span.setStatus(StatusCode.Error, e.getMessage) *>
              onFailure(repository, task, e)
                .handleErrorWith { completionError =>
                  logger.error(completionError)(s"Failed to record failure for $taskId")
                }
        }
      }
  }

  private def runTaskUsingReservedPermit(
    repository: SchedulerRepository,
    task: Task[?],
    sem: Semaphore[IO]
  ): IO[Unit] = {
    val work: IO[Unit] =
      Resource
        .make {
          heartbeatLoop(repository, task).start
        } { fiber =>
          fiber.cancel
        }
        .use { _ =>
          executeAndComplete(repository, task)
        }

    work.guarantee(sem.release)
  }

  private def deadExecutionDetectionLoop(repository: SchedulerRepository) = {
    val deadThresholdMillis = heartbeatInterval.toMillis * missedHeartbeatLimit
    val detectionInterval   = heartbeatInterval * 2

    val oneCheck: IO[Unit] =
      Clock[IO].realTimeInstant.flatMap { now =>
        val staleThreshold = now.minusMillis(deadThresholdMillis)
        transactor
          .inSession {
            repository.reviveStaleExecutions(staleThreshold)
          }
          .flatMap { revived =>
            if (revived > 0)
              logger.warn(s"Revived $revived dead execution(s) with heartbeat older than $staleThreshold")
            else
              IO.unit
          }
          .handleErrorWith { e =>
            logger.error(e)("Error during dead execution detection")
          }
      } *> IO.sleep(detectionInterval)

    oneCheck.foreverM.background
  }

  private def mainLoop(repository: SchedulerRepository): Resource[IO, Unit] =
    for {
      sem <- Resource.eval(Semaphore[IO](concurrency.toLong))
      sup <- Supervisor[IO]
      _   <- deadExecutionDetectionLoop(repository)
      _   <- pollLoop(repository, sem, sup)
    } yield ()

  private def pollLoop(
    repository: SchedulerRepository,
    sem: Semaphore[IO],
    sup: Supervisor[IO]
  ) = {
    (for {
      reserved <- reservePermits(sem, concurrency)
      _ <- if (reserved == 0) {
             IO.sleep(pollingInterval)
           } else {
             transactor
               .inSession {
                 repository.pickAndMark(reserved)
               }
               .recoverWith { case t =>
                 logger.error(t)("Error while picking tasks") *>
                   IO.pure((Nil, Nil))
               }
               .flatMap { case (tasks, unreconstructable) =>
                 val logUnreconstructable =
                   if (unreconstructable.nonEmpty)
                     logger.error(s"Failed to reconstruct tasks (decode error or unknown), deleted: ${unreconstructable.mkString(", ")}")
                   else IO.unit
                 val used   = tasks.size
                 val unused = reserved - used

                 val startWorkers =
                   tasks.traverse_ { task =>
                     sup.supervise(runTaskUsingReservedPermit(repository, task, sem)).void
                   }

                 val releaseSurplus =
                   if (unused > 0) sem.releaseN(unused.toLong) else IO.unit

                 val sleepIfIdle =
                   if (used >= reserved) IO.unit else IO.sleep(pollingInterval)

                 logUnreconstructable *> startWorkers *> releaseSurplus *> sleepIfIdle
               }
           }
    } yield ()).foreverM.background
  }
}

class RunningScheduler private[task] (
  repository: SchedulerRepository,
  transactor: Transactor
) {
  def schedule[A](task: Task[A]): IO[Boolean] =
    transactor.inSession(repository.save(task))
}
