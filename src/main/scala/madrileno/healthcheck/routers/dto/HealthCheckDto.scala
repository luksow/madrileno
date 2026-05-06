package madrileno.healthcheck.routers.dto

import io.scalaland.chimney.dsl.*
import madrileno.main.AppConfig
import madrileno.utils.json.JsonProtocol.*

final case class HealthCheckDto(
  name: String,
  environment: String,
  version: String,
  apiVersion: String)
    derives Encoder.AsObject,
      Decoder

object HealthCheckDto {
  def apply(appConfig: AppConfig): HealthCheckDto = appConfig.into[HealthCheckDto].transform
}
