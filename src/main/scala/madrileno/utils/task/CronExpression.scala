package madrileno.utils.task

import cron4s.Cron
import cron4s.expr.CronExpr
import cron4s.lib.javatime.*
import cron4s.syntax.all.*

import java.time.Instant
import java.time.ZoneOffset
import java.time.ZoneId

opaque type CronExpression = CronExpr
object CronExpression {
  def parse(expression: String): Either[String, CronExpression] =
    Cron.parse(expression).left.map(_.getMessage)

  def unsafeParse(expression: String): CronExpression =
    parse(expression).fold(msg => throw new IllegalArgumentException(s"Invalid cron expression: $expression, error: $msg"), identity)

  extension (cron: CronExpression) {
    def nextFrom(now: Instant, zoneId: ZoneId = ZoneOffset.UTC): Option[Instant] = {
      cron.next(now.atZone(zoneId).toLocalDateTime()).map(_.atZone(zoneId).toInstant())
    }
  }
}
