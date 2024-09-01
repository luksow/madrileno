package madrileno.healthcheck.routers.dto

import madrileno.main.AppConfig
import madrileno.user.domain.UserId
import madrileno.utils.json.JsonProtocol.*
import io.scalaland.chimney.dsl.*

import scala.concurrent.duration.FiniteDuration

final case class DbStatus(status: String, latency: Option[Long]) derives Encoder.AsObject

final case class HealthCheck(
  name: String,
  environment: String,
  version: String,
  apiVersion: String,
  userId: Option[UserId],
  db: Option[DbStatus])
    derives Encoder.AsObject

object HealthCheck {
  def apply(appConfig: AppConfig): HealthCheck = {
    appConfig
      .into[HealthCheck]
      .withFieldConst(_.userId, None)
      .withFieldConst(_.db, None)
      .transform
  }

  def apply(
    appConfig: AppConfig,
    userId: UserId,
    rtt: Option[FiniteDuration]
  ): HealthCheck = {
    val dbStatus = rtt.map { latency => DbStatus("Up", Some(latency.toMillis)) }.getOrElse(DbStatus("Down", None))

    appConfig
      .into[HealthCheck]
      .withFieldConst(_.userId, Some(userId))
      .withFieldConst(_.db, Some(dbStatus))
      .transform
  }
}
