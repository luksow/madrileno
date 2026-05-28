package madrileno.utils.task

import cats.effect.IO
import madrileno.utils.http.BaseRouter
import org.http4s.headers.`Content-Type`
import org.http4s.{EntityEncoder, MediaType, Response}
import pl.iterators.stir.server.Route
import scalatags.Text.TypedTag

import java.time.Instant

class SchedulerAdminRouter(
  recurringTasks: List[Task[?]],
  oneTimeTasks: List[OneTimeTask[?]],
  customTasks: List[CustomTask[?]],
  schedulerClient: SchedulerClient)
    extends BaseRouter {

  val routes: Route = (get & pathPrefix("jobs") & pathEndOrSingleSlash) {
    complete {
      schedulerClient.listTasks.map { taskRows =>
        Response[IO]()
          .withEntity(jobsPage(taskRows))(using EntityEncoder.stringEncoder[IO].withContentType(`Content-Type`(MediaType.text.html)))
      }
    }
  }

  private def jobsPage(taskRows: List[TaskRow]): String = {
    import scalatags.Text.all.{head as stHead, *}
    import scalatags.Text.tags2.title

    val taskRowsByName = taskRows.groupBy(_.taskName)
    val recurringNames = recurringTasks.map(_.descriptor.taskName).toSet
    val knownNames     = recurringNames ++ oneTimeTasks.map(_.descriptor.taskName) ++ customTasks.map(_.descriptor.taskName)

    val recurringEntries = recurringTasks.map(t => t -> taskRowsByName.get(t.descriptor.taskName).flatMap(_.headOption))
    val running          = taskRows.filter(_.picked)
    val failing          = taskRows.filter(_.consecutiveFailures.exists(_ > 0)).sortBy(_.lastFailure.map(_.toEpochMilli * -1L))
    val orphaned         = taskRows.filterNot(r => knownNames.contains(r.taskName))

    html(
      stHead(meta(charset := "UTF-8"), title("Jobs")),
      body(style := "font-family:system-ui,sans-serif;max-width:1100px;margin:24px auto;padding:0 16px;color:#222")(
        h1(style := "margin:0 0 24px 0")("Jobs"),
        recurringSection(recurringEntries),
        runningSection(running),
        failingSection(failing),
        if (orphaned.nonEmpty) orphanedSection(orphaned) else emptyFrag,
        registeredTypesSection()
      )
    ).render
  }

  private def registeredTypesSection(): TypedTag[String] = {
    import scalatags.Text.all.*
    val recurringNames = recurringTasks.map(t => s"${t.descriptor.taskName} (${renderSchedule(t.schedule)})").sorted
    val oneTimeNames   = oneTimeTasks.map(_.descriptor.taskName).sorted
    val customNames    = customTasks.map(_.descriptor.taskName).sorted
    div(
      h2("Registered task types"),
      sectionTable(
        List("Kind", "Name"),
        recurringNames.map(n => List("recurring", n)) ++
          oneTimeNames.map(n => List("one-time", n)) ++
          customNames.map(n => List("custom", n))
      )
    )
  }

  private def recurringSection(entries: List[(Task[?], Option[TaskRow])]): TypedTag[String] = {
    import scalatags.Text.all.*
    div(
      h2(s"Recurring tasks (${entries.size})"),
      if (entries.isEmpty) emptyHint("None registered.")
      else
        sectionTable(
          List("Name", "Schedule", "Next run", "Last success", "Last failure", "Failures", "Picked by"),
          entries.map { case (task, row) =>
            List(
              task.descriptor.taskName,
              renderSchedule(task.schedule),
              row.fold("—")(r => formatInstant(r.nextExecution)),
              row.flatMap(_.lastSuccess).fold("—")(formatInstant),
              row.flatMap(_.lastFailure).fold("—")(formatInstant),
              row.flatMap(_.consecutiveFailures).filter(_ > 0).fold("—")(_.toString),
              row.flatMap(_.pickedBy).getOrElse("—")
            )
          }
        )
    )
  }

  private def runningSection(taskRows: List[TaskRow]): TypedTag[String] = {
    import scalatags.Text.all.*
    div(
      h2(s"Currently running (${taskRows.size})"),
      if (taskRows.isEmpty) emptyHint("Nothing in flight.")
      else
        sectionTable(
          List("Task", "Picked by", "Last heartbeat", "Payload"),
          taskRows.map(r =>
            List(s"${r.taskName}/${r.taskInstance}", r.pickedBy.getOrElse("—"), r.lastHeartbeat.fold("—")(formatInstant), payloadPreview(r))
          )
        )
    )
  }

  private def failingSection(taskRows: List[TaskRow]): TypedTag[String] = {
    import scalatags.Text.all.*
    div(
      h2(s"Failing (${taskRows.size})"),
      p(style := "color:#666;margin:0 0 8px 0;font-size:13px")(
        "Tasks with one or more consecutive failures. Showing the most recent first; up to 50."
      ),
      if (taskRows.isEmpty) emptyHint("No tasks are failing.")
      else
        sectionTable(
          List("Task", "Last failure", "Consecutive failures", "Next retry", "Payload"),
          taskRows
            .take(50)
            .map(r =>
              List(
                s"${r.taskName}/${r.taskInstance}",
                r.lastFailure.fold("—")(formatInstant),
                r.consecutiveFailures.fold("—")(_.toString),
                formatInstant(r.nextExecution),
                payloadPreview(r)
              )
            )
        )
    )
  }

  private def orphanedSection(taskRows: List[TaskRow]): TypedTag[String] = {
    import scalatags.Text.all.*
    div(
      h2(s"Orphaned rows (${taskRows.size})"),
      p(style := "color:#666;margin:0 0 8px 0;font-size:13px")(
        "Rows whose task_name is not registered in the running app — usually tasks renamed or removed in code. The scheduler clears these lazily on pick."
      ),
      sectionTable(
        List("Task", "Next execution", "Last failure", "Failures"),
        taskRows.map(r =>
          List(
            s"${r.taskName}/${r.taskInstance}",
            formatInstant(r.nextExecution),
            r.lastFailure.fold("—")(formatInstant),
            r.consecutiveFailures.fold("—")(_.toString)
          )
        )
      )
    )
  }

  private def sectionTable(headers: List[String], taskRows: Seq[List[String]]): TypedTag[String] = {
    import scalatags.Text.all.*
    table(style := "border-collapse:collapse;width:100%;font-size:14px;margin-bottom:24px")(
      thead(tr(style := "background:#f5f5f5;text-align:left")(headers.map(h => th(style := "padding:6px 10px;border-bottom:1px solid #ddd")(h)))),
      tbody(
        taskRows.map(cells =>
          tr(style := "border-bottom:1px solid #eee")(
            cells.map(c =>
              td(style := "padding:6px 10px;vertical-align:top;font-family:ui-monospace,Menlo,monospace;font-size:13px;word-break:break-word")(c)
            )
          )
        )
      )
    )
  }

  private def emptyHint(text: String): TypedTag[String] = {
    import scalatags.Text.all.*
    p(style := "color:#666;font-style:italic;margin-bottom:24px")(text)
  }

  private def emptyFrag: TypedTag[String] = {
    import scalatags.Text.all.*
    span()
  }

  private def formatInstant(i: Instant): String = i.toString

  private def renderSchedule(s: Schedule): String = s match {
    case Schedule.Once                              => "once (immediate)"
    case Schedule.OnceAt(at)                        => s"once at ${formatInstant(at)}"
    case Schedule.RecurringWithFixedRate(every, _)  => s"every $every (fixed rate)"
    case Schedule.RecurringWithFixedDelay(after, _) => s"every $after (fixed delay)"
    case Schedule.Cron(expression)                  => s"cron: ${expression.toString}"
    case Schedule.NextAt(at, _)                     => s"custom, next at ${formatInstant(at)}"
  }

  private def payloadPreview(row: TaskRow): String = {
    val raw = row.taskData.noSpaces
    if (raw.length > 120) raw.take(117) + "…" else raw
  }
}
