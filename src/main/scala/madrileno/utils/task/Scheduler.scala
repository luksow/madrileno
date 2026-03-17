package madrileno.utils.task

import cats.effect.std.{Semaphore, Supervisor}
import cats.syntax.all.*
import cats.effect.{Clock, IO, Resource}
import io.circe.{Decoder, Encoder, Json}
import madrileno.utils.db.dsl.*
import madrileno.utils.db.transactor.{DB, Transactor}
import madrileno.utils.observability.{LoggingSupport, TelemetryContext}
import skunk.{Codec, Session}
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

final case class Task[A](
  taskInstance: String,
  descriptor: TaskDescriptor[A],
  payload: A,
  execution: Task[A] => IO[Unit | A | Schedule.NextAt[A]],
  version: Long,
  priority: Short,
  schedule: Schedule) {}

final case class OneTimeTask[A](descriptor: TaskDescriptor[A], execution: Task[A] => IO[Unit]) {
  def instance(
    taskInstance: String,
    payload: A,
    at: Instant = Task.AtMarker,
    priority: Short = Task.DefaultPriority
  ): Task[A] = {
    Task(
      taskInstance = taskInstance,
      descriptor = descriptor,
      payload = payload,
      execution = execution,
      version = 0L,
      priority = priority,
      schedule = if (at == Task.AtMarker) Schedule.Once else Schedule.OnceAt(at)
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
  val AtMarker: Instant      = Instant.MIN
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
  consecutiveFails: Option[Int],
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
      consecutiveFails = None,
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

class SchedulerRepository(
  schedulerName: String,
  startTasks: List[Task[?]],
  oneTimeTasks: List[OneTimeTask[?]]
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

  def pickAndMark(limit: Int): DB[List[Task[?]]] = {
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
      session.execute(query)((Some(schedulerName), Some(now), now, limit)).map { rows =>
        rows.map { row =>
          oneTimeTasks
            .find(t => t.descriptor.taskName == row.taskName)
            .map { oneTimeTask =>
              val payload = oneTimeTask.descriptor.decoder.decodeJson(row.taskData)
              payload match {
                case Left(error) =>
                  Task(
                    taskInstance = row.taskInstance,
                    descriptor = TaskDescriptor[Unit](oneTimeTask.descriptor.taskName),
                    payload = (),
                    execution = _ =>
                      IO.raiseError(new Exception(s"Failed to decode one-time task payload for task ${row.taskName}/${row.taskInstance}: $error")),
                    version = row.version,
                    priority = row.priority,
                    schedule = Schedule.Once
                  )
                case Right(value) =>
                  Task(
                    taskInstance = row.taskInstance,
                    descriptor = oneTimeTask.descriptor,
                    payload = value,
                    execution = oneTimeTask.execution,
                    version = row.version,
                    priority = row.priority,
                    schedule = Schedule.Once
                  )
              }
            }
            .orElse {
              startTasks.find(t => t.descriptor.taskName == row.taskName).map { recurringTask =>
                val payload = recurringTask.descriptor.decoder.decodeJson(row.taskData)
                payload match {
                  case Left(error) =>
                    Task(
                      taskInstance = row.taskInstance,
                      descriptor = TaskDescriptor[Unit](recurringTask.descriptor.taskName),
                      payload = (),
                      execution = _ =>
                        IO.raiseError(new Exception(s"Failed to decode recurring task payload for task ${row.taskName}/${row.taskInstance}: $error")),
                      version = row.version,
                      priority = row.priority,
                      schedule = recurringTask.schedule
                    )
                  case Right(value) =>
                    Task(
                      taskInstance = row.taskInstance,
                      descriptor = recurringTask.descriptor,
                      payload = value,
                      execution = recurringTask.execution,
                      version = row.version,
                      priority = row.priority,
                      schedule = recurringTask.schedule
                    )
                }
              }
            }
            .getOrElse {
              Task(
                taskInstance = row.taskInstance,
                descriptor = TaskDescriptor[Unit](row.taskName),
                payload = (),
                execution = _ => IO.raiseError(new Exception(s"Unknown task picked: ${row.taskName}/${row.taskInstance}")),
                version = row.version,
                priority = row.priority,
                schedule = Schedule.Once
              )
            }
        }
      }
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

  def run(recurringTasks: List[Task[?]], oneTimeTasks: List[OneTimeTask[?]]): Resource[IO, Unit] = {
    (for {
      schedulerName <- Resource.eval(schedulerNameToUse)
      repository    <- Resource.eval(setup(schedulerName, recurringTasks, oneTimeTasks))
      _             <- mainLoop(repository)
    } yield ()).onFinalize {
      logger.info("Shutting down scheduler...")
    }
  }

  private def setup(
    schedulerName: String,
    startTasks: List[Task[?]],
    oneTimeTasks: List[OneTimeTask[?]]
  ): IO[SchedulerRepository] = {
    val repository = new SchedulerRepository(schedulerName, startTasks, oneTimeTasks)
    logger.info(
      s"Starting scheduler with concurrency=$concurrency, pollingInterval=$pollingInterval, heartbeatInterval=$heartbeatInterval, missedHeartbeatLimit=$missedHeartbeatLimit, " +
        s"initial tasks=${startTasks.map(t => s"${t.descriptor.taskName}/${t.taskInstance}, registered one time tasks=${oneTimeTasks.map(_.descriptor.taskName)}")}}"
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
          logger.error(e)(s"Heartbeat failed for ${task.descriptor.taskName}/${task.taskInstance}")
        } *> IO.sleep(heartbeatInterval)

    oneBeat.foreverM
  }

  private def runTaskUsingReservedPermit(
    repository: SchedulerRepository,
    task: Task[?],
    sem: Semaphore[IO]
  ): IO[Unit] = {
    val run = task.execution(task)

    val withHeartbeat: IO[Unit] =
      Resource
        .make {
          heartbeatLoop(repository, task).start
        } { fiber =>
          fiber.cancel
        }
        .use { _ =>
          run.attempt.flatMap {
            case Right(_) =>
              IO.unit
            case Left(e) =>
              logger.error(e)(s"Task ${task.descriptor.taskName}/${task.taskInstance} failed")
          }
        }

    withHeartbeat.guarantee(sem.release)
  }

  private def mainLoop(repository: SchedulerRepository): Resource[IO, Unit] =
    for {
      sem <- Resource.eval(Semaphore[IO](concurrency.toLong))
      sup <- Supervisor[IO]
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
                 logger.error(t)("Error while picking tasks") *> IO.pure(List.empty)
               }
               .flatMap { tasks =>
                 val used   = tasks.size
                 val unused = reserved - used

                 val startWorkers =
                   tasks.traverse_ { task =>
                     sup.supervise(runTaskUsingReservedPermit(repository, task, sem)).void
                   }

                 val releaseSurplus =
                   if (unused > 0) sem.releaseN(unused.toLong) else IO.unit

                 startWorkers *> releaseSurplus *> IO.sleep(pollingInterval)
               }
           }
    } yield ()).foreverM.background
  }
}
