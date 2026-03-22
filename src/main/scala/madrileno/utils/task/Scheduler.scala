package madrileno.utils.task

import cats.effect.std.{Semaphore, Supervisor}
import cats.syntax.all.*
import cats.effect.{Clock, IO, Resource}
import io.circe.{Decoder, Encoder, Json}
import madrileno.utils.db.dsl.*
import madrileno.utils.db.transactor.{DB, Transactor}
import madrileno.utils.observability.{LoggingSupport, TelemetryContext}
import skunk.{Codec, Session}
import skunk.data.Completion
import skunk.codec.all.*
import skunk.circe.codec.all.*
import skunk.implicits.*

import java.net.InetAddress
import scala.concurrent.duration.*
import java.time.Instant

final case class TaskDescriptor[A](taskName: String)(using val encoder: Encoder[A], val decoder: Decoder[A])

sealed trait Schedule
sealed trait Once      extends Schedule
sealed trait Recurring extends Schedule
sealed trait Custom    extends Schedule
object Schedule {
  case object Once                                                                        extends Once
  case class OnceAt(at: Instant)                                                          extends Once
  case class RecurringWithFixedRate(every: Duration, initialDelay: Duration = 0.seconds)  extends Recurring
  case class RecurringWithFixedDelay(after: Duration, initialDelay: Duration = 0.seconds) extends Recurring
  case class NextAt[A](at: Instant, payload: A)                                           extends Custom
}

final case class Task[A] private[task] (
  taskInstance: String,
  descriptor: TaskDescriptor[A],
  payload: A,
  execution: Task[A] => IO[Unit | A | Schedule.NextAt[A]],
  version: Long,
  priority: Short,
  schedule: Schedule,
  consecutiveFailures: Option[Int] = None,
  scheduledAt: Option[Instant] = None) {
  def taskId: String = s"${descriptor.taskName}/$taskInstance"
}

final case class OneTimeTask[A](descriptor: TaskDescriptor[A], execution: Task[A] => IO[Unit]) {
  def instance(
    taskInstance: String,
    payload: A,
    at: Option[Instant] = None,
    priority: Short = Task.DefaultPriority
  ): Task[A] = {
    Task(
      taskInstance = taskInstance,
      descriptor = descriptor,
      payload = payload,
      execution = execution,
      version = 0L,
      priority = priority,
      schedule = at.fold[Schedule](Schedule.Once)(Schedule.OnceAt(_))
    )
  }
}

final case class CustomTask[A](descriptor: TaskDescriptor[A], execution: Task[A] => IO[Unit | Schedule.NextAt[A]]) {
  def instance(
    taskInstance: String,
    payload: A,
    firstAt: Instant,
    priority: Short = Task.DefaultPriority
  ): Task[A] = {
    Task(
      taskInstance = taskInstance,
      descriptor = descriptor,
      payload = payload,
      execution = execution,
      version = 0L,
      priority = priority,
      schedule = Schedule.NextAt(firstAt, payload)
    )
  }
}

object Task {
  def recurring[A](
    name: String,
    schedule: Recurring,
    payload: A = (),
    priority: Short = DefaultPriority
  )(
    execution: Task[A] => IO[Unit | A]
  )(using
    Encoder[A],
    Decoder[A]
  ): Task[A] = {
    Task(
      taskInstance = s"recurring",
      descriptor = TaskDescriptor[A](name),
      payload = payload,
      execution = execution,
      version = 0L,
      priority = priority,
      schedule = schedule
    )
  }

  def oneTime[A](descriptor: TaskDescriptor[A])(execution: Task[A] => IO[Unit]): OneTimeTask[A] = {
    OneTimeTask(descriptor, execution)
  }

  def custom[A](descriptor: TaskDescriptor[A])(execution: A => IO[Unit | Schedule.NextAt[A]]): CustomTask[A] = {
    CustomTask(descriptor, task => execution(task.payload))
  }

  val DefaultPriority: Short = 0
}

private[task] case class TaskRow(
  taskName: String,
  taskInstance: String,
  taskData: Json,
  nextExecution: Instant,
  picked: Boolean,
  pickedBy: Option[String],
  lastSuccess: Option[Instant],
  lastFailure: Option[Instant],
  consecutiveFailures: Option[Int],
  lastHeartbeat: Option[Instant],
  version: Long,
  priority: Short)

object TaskRow {
  def fromTask[A](task: Task[A], now: Instant): TaskRow = {
    val nextExecution = task.schedule match {
      case Schedule.Once                                     => now
      case Schedule.OnceAt(at)                               => at
      case Schedule.RecurringWithFixedRate(_, initialDelay)  => now.plusMillis(initialDelay.toMillis)
      case Schedule.RecurringWithFixedDelay(_, initialDelay) => now.plusMillis(initialDelay.toMillis)
      case Schedule.NextAt(at, _)                            => at
    }

    TaskRow(
      taskName = task.descriptor.taskName,
      taskInstance = task.taskInstance,
      taskData = task.descriptor.encoder(task.payload),
      nextExecution = nextExecution,
      picked = false,
      pickedBy = None,
      lastSuccess = None,
      lastFailure = None,
      consecutiveFailures = None,
      lastHeartbeat = None,
      version = task.version,
      priority = task.priority
    )
  }
}

object TaskRowTable extends Table[TaskRow]("scheduled_task") {
  val taskName: Column[String]                 = column("task_name", text)
  val taskInstance: Column[String]             = column("task_instance", text)
  val taskData: Column[Json]                   = column("task_data", jsonb)
  val nextExecution: Column[Instant]           = column("next_execution", timestamptz.asInstant)
  val picked: Column[Boolean]                  = column("picked", bool)
  val pickedBy: Column[Option[String]]         = column("picked_by", text.opt)
  val lastSuccess: Column[Option[Instant]]     = column("last_success", timestamptz.asInstant.opt)
  val lastFailure: Column[Option[Instant]]     = column("last_failure", timestamptz.asInstant.opt)
  val consecutiveFailures: Column[Option[Int]] = column("consecutive_failures", int4.opt)
  val lastHeartbeat: Column[Option[Instant]]   = column("last_heartbeat", timestamptz.asInstant.opt)
  val version: Column[Long]                    = column("version", int8)
  val priority: Column[Short]                  = column("priority", int2)

  def mapping: (List[Column[?]], Codec[TaskRow]) =
    (
      taskName,
      taskInstance,
      taskData,
      nextExecution,
      picked,
      pickedBy,
      lastSuccess,
      lastFailure,
      consecutiveFailures,
      lastHeartbeat,
      version,
      priority
    )
}

private[task] class SchedulerRepository(
  schedulerName: String,
  startTasks: List[Task[?]],
  oneTimeTasks: List[OneTimeTask[?]],
  customTasks: List[CustomTask[?]]
)(using clock: Clock[IO]) {
  def save[A](task: Task[A]): DB[Task[A]] = {
    val session = summon[Session[IO]]

    clock.realTimeInstant.flatMap { now =>
      val row = TaskRow.fromTask(task, now)
      session
        .unique(sql"""INSERT INTO ${table.n} VALUES (${table.c})
           ON CONFLICT (${table.taskName.n}, ${table.taskInstance.n}) DO UPDATE SET
           ${table.taskData.n} = ${table.taskData.n("EXCLUDED")},
           ${table.nextExecution.n} = ${table.nextExecution.n("EXCLUDED")},
           ${table.priority.n} = ${table.priority.n("EXCLUDED")}
           RETURNING ${table.*}""".query(table.c))(row)
        .map(_ => task)
    }
  }

  def updateHeartbeat(task: Task[?]): DB[Unit] = {
    val session = summon[Session[IO]]

    clock.realTimeInstant.flatMap { now =>
      val command =
        sql"""UPDATE ${table.n} SET
          ${table.lastHeartbeat.n} = ${table.lastHeartbeat.c}
        WHERE ${table.taskName.n} = ${table.taskName.c}
          AND ${table.taskInstance.n} = ${table.taskInstance.c}
          AND ${table.version.n} = ${table.version.c}
       """.command
      session
        .execute(command)((Some(now), task.descriptor.taskName, task.taskInstance, task.version))
        .void
    }
  }

  def reschedule[A](
    task: Task[A],
    nextExecution: Instant,
    newPayload: Option[A] = None
  ): DB[Boolean] = {
    val session = summon[Session[IO]]

    clock.realTimeInstant.flatMap { now =>
      val taskData = newPayload.fold(task.descriptor.encoder(task.payload))(task.descriptor.encoder(_))
      val command =
        sql"""UPDATE ${table.n} SET
          ${table.picked.n} = false,
          ${table.pickedBy.n} = NULL,
          ${table.lastHeartbeat.n} = NULL,
          ${table.lastSuccess.n} = ${table.lastSuccess.c},
          ${table.consecutiveFailures.n} = ${table.consecutiveFailures.c},
          ${table.taskData.n} = ${table.taskData.c},
          ${table.nextExecution.n} = ${table.nextExecution.c},
          ${table.version.n} = ${table.version.n} + 1
        WHERE ${table.taskName.n} = ${table.taskName.c}
          AND ${table.taskInstance.n} = ${table.taskInstance.c}
          AND ${table.version.n} = ${table.version.c}
       """.command
      session
        .execute(command)((Some(now), Some(0), taskData, nextExecution, task.descriptor.taskName, task.taskInstance, task.version))
        .map(_ != Completion.Update(0))
    }
  }

  def markFailure[A](
    task: Task[A],
    nextExecution: Instant,
    consecutiveFailures: Int
  ): DB[Boolean] = {
    val session = summon[Session[IO]]

    clock.realTimeInstant.flatMap { now =>
      val command =
        sql"""UPDATE ${table.n} SET
          ${table.picked.n} = false,
          ${table.pickedBy.n} = NULL,
          ${table.lastHeartbeat.n} = NULL,
          ${table.lastFailure.n} = ${table.lastFailure.c},
          ${table.consecutiveFailures.n} = ${table.consecutiveFailures.c},
          ${table.nextExecution.n} = ${table.nextExecution.c},
          ${table.version.n} = ${table.version.n} + 1
        WHERE ${table.taskName.n} = ${table.taskName.c}
          AND ${table.taskInstance.n} = ${table.taskInstance.c}
          AND ${table.version.n} = ${table.version.c}
       """.command
      session
        .execute(command)((Some(now), Some(consecutiveFailures), nextExecution, task.descriptor.taskName, task.taskInstance, task.version))
        .map(_ != Completion.Update(0))
    }
  }

  def remove(task: Task[?]): DB[Boolean] = {
    val session = summon[Session[IO]]

    val command =
      sql"""DELETE FROM ${table.n}
        WHERE ${table.taskName.n} = ${table.taskName.c}
          AND ${table.taskInstance.n} = ${table.taskInstance.c}
          AND ${table.version.n} = ${table.version.c}
       """.command
    session
      .execute(command)((task.descriptor.taskName, task.taskInstance, task.version))
      .map(_ != Completion.Delete(0))
  }

  private def reconstructTask[A](
    row: TaskRow,
    descriptor: TaskDescriptor[A],
    execution: Task[A] => IO[Unit | A | Schedule.NextAt[A]],
    schedule: A => Schedule,
    scheduledAt: Option[Instant] = None
  ): Option[Task[?]] = {
    descriptor.decoder.decodeJson(row.taskData) match {
      case Right(value) =>
        Some(Task(
          taskInstance = row.taskInstance,
          descriptor = descriptor,
          payload = value,
          execution = execution,
          version = row.version,
          priority = row.priority,
          schedule = schedule(value),
          consecutiveFailures = row.consecutiveFailures,
          scheduledAt = scheduledAt
        ))
      case Left(_) =>
        None
    }
  }

  private def removePickedRow(row: TaskRow): DB[Unit] = {
    val session = summon[Session[IO]]
    val command =
      sql"""UPDATE ${table.n} SET
        ${table.picked.n} = false,
        ${table.pickedBy.n} = NULL,
        ${table.lastHeartbeat.n} = NULL,
        ${table.version.n} = ${table.version.n} + 1
      WHERE ${table.taskName.n} = ${table.taskName.c}
        AND ${table.taskInstance.n} = ${table.taskInstance.c}
        AND ${table.version.n} = ${table.version.c}
     """.command
    session
      .execute(command)((row.taskName, row.taskInstance, row.version))
      .void
  }

  def pickAndMark(limit: Int): DB[(List[Task[?]], List[String])] = {
    val session = summon[Session[IO]]

    clock.realTimeInstant.flatMap { now =>
      val query =
        sql"""WITH locked_executions as (
          UPDATE ${table.n} st1 SET
            ${table.picked.n} = true,
            ${table.pickedBy.n} = ${table.pickedBy.c},
            ${table.lastHeartbeat.n} = ${table.lastHeartbeat.c},
            ${table.version.n} = ${table.version.n} + 1
          WHERE (st1.${table.taskName.n}, st1.${table.taskInstance.n}) IN (
            SELECT st2.${table.taskName.n}, st2.${table.taskInstance.n}
            FROM ${table.n} st2
            WHERE st2.${table.picked.n} = false AND st2.${table.nextExecution.n} <= ${table.nextExecution.c}
            ORDER BY st2.${table.priority.n} DESC, st2.${table.nextExecution.n} ASC
            FOR UPDATE SKIP LOCKED
            LIMIT $int4
          ) RETURNING st1.*
    )
    SELECT * FROM locked_executions ORDER BY ${table.priority.n} DESC, ${table.nextExecution.n} ASC
       """.query(table.c)
      session.execute(query)((Some(schedulerName), Some(now), now, limit)).flatMap { rows =>
        val (failures, successes) = rows.partitionMap { row =>
          val found = oneTimeTasks
            .find(_.descriptor.taskName == row.taskName)
            .flatMap { t =>
              reconstructTask(row, t.descriptor, t.execution, _ => Schedule.Once, Some(row.nextExecution))
            }
            .orElse {
              customTasks
                .find(_.descriptor.taskName == row.taskName)
                .flatMap { t =>
                  reconstructTask(row, t.descriptor, t.execution, v => Schedule.NextAt(row.nextExecution, v), Some(row.nextExecution))
                }
            }
            .orElse {
              startTasks
                .find(_.descriptor.taskName == row.taskName)
                .flatMap { t =>
                  reconstructTask(row, t.descriptor, t.execution, _ => t.schedule)
                }
            }

          found.toRight(row)
        }

        failures.traverse(removePickedRow).as((successes, failures.map(r => s"${r.taskName}/${r.taskInstance}")))
      }
    }
  }

  def reviveStaleExecutions(staleThreshold: Instant): DB[Int] = {
    val session = summon[Session[IO]]

    clock.realTimeInstant.flatMap { now =>
      val command =
        sql"""UPDATE ${table.n} SET
          ${table.picked.n} = false,
          ${table.pickedBy.n} = NULL,
          ${table.lastHeartbeat.n} = NULL,
          ${table.nextExecution.n} = ${table.nextExecution.c},
          ${table.version.n} = ${table.version.n} + 1
        WHERE ${table.picked.n} = true
          AND ${table.lastHeartbeat.n} <= ${table.lastHeartbeat.c}
       """.command
      session
        .execute(command)((now, Some(staleThreshold)))
        .map { case Completion.Update(n) => n; case _ => 0 }
    }
  }

  private val table = TaskRowTable
}

enum SchedulerName {
  case Hostname
  case Fixed(name: String) extends SchedulerName
}

class Scheduler(
  transactor: Transactor,
  concurrency: Int = 10,
  pollingInterval: Duration = 10.seconds,
  heartbeatInterval: Duration = 5.minutes,
  missedHeartbeatLimit: Int = 6,
  retryBaseDelay: Duration = 30.seconds,
  retryBackoffRate: Double = 1.5,
  retryMaxDelay: Duration = 1.hour,
  maxRetries: Option[Int] = None,
  schedulerName: SchedulerName = SchedulerName.Hostname
)(using
  Clock[IO],
  TelemetryContext)
    extends LoggingSupport {
  private val schedulerNameToUse: IO[String] = schedulerName match {
    case SchedulerName.Hostname =>
      IO.blocking {
        InetAddress.getLocalHost.getHostName
      }.recover { case _ =>
        "unknown-host"
      }
    case SchedulerName.Fixed(name) =>
      IO.pure(name)
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
          startTasks.traverse(repository.save)
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
    val taskId = task.taskId

    task.execution(task).attempt.flatMap {
      case Right(result) =>
        onSuccess(repository, task, result)
          .handleErrorWith { e =>
            logger.error(e)(s"Failed to complete $taskId after success")
          }
      case Left(e) =>
        onFailure(repository, task, e)
          .handleErrorWith { completionError =>
            logger.error(completionError)(s"Failed to record failure for $taskId")
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
                     logger.error(s"Failed to reconstruct tasks (decode error or unknown), released: ${unreconstructable.mkString(", ")}")
                   else IO.unit
                 val used   = tasks.size
                 val unused = reserved - used

                 val startWorkers =
                   tasks.traverse_ { task =>
                     sup.supervise(runTaskUsingReservedPermit(repository, task, sem)).void
                   }

                 val releaseSurplus =
                   if (unused > 0) sem.releaseN(unused.toLong) else IO.unit

                 logUnreconstructable *> startWorkers *> releaseSurplus *> IO.sleep(pollingInterval)
               }
           }
    } yield ()).foreverM.background
  }
}

class RunningScheduler private[task] (
  repository: SchedulerRepository,
  transactor: Transactor
) {
  def schedule[A](task: Task[A]): IO[Task[A]] =
    transactor.inSession(repository.save(task))
}
