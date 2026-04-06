package madrileno.healthcheck.routers.dto

import io.scalaland.chimney.dsl.*
import madrileno.main.AppConfig
import madrileno.user.domain.UserId
import madrileno.utils.json.JsonProtocol.*

import scala.concurrent.duration.FiniteDuration

final case class DbStatus(status: String, latency: Option[Long]) derives Encoder.AsObject, Decoder

final case class HealthCheckDto(
  name: String,
  environment: String,
  version: String,
  apiVersion: String,
  userId: Option[UserId],
  db: Option[DbStatus])
    derives Encoder.AsObject,
      Decoder

object HealthCheckDto {
  def apply(appConfig: AppConfig): HealthCheckDto = {
    appConfig
      .into[HealthCheckDto]
      .withFieldConst(_.userId, None)
      .withFieldConst(_.db, None)
      .transform
  }

  def apply(
    appConfig: AppConfig,
    userId: UserId,
    rtt: Option[FiniteDuration]
  ): HealthCheckDto = {
    val dbStatus = rtt.map { latency => DbStatus("Up", Some(latency.toMillis)) }.getOrElse(DbStatus("Down", None))

    appConfig
      .into[HealthCheckDto]
      .withFieldConst(_.userId, Some(userId))
      .withFieldConst(_.db, Some(dbStatus))
      .transform
  }
}
