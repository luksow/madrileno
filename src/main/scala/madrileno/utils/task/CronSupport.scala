package madrileno.utils.task

import cron4s.Cron
import cron4s.lib.javatime.*
import cron4s.syntax.all.*

import java.time.Instant

private[task] object CronSupport {
  def nextFrom(expression: String, now: Instant): Option[Instant] =
    Cron.parse(expression).toOption.flatMap(_.next(now))
}
