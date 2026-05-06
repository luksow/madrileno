package madrileno.healthcheck.services

import cats.effect.IO
import madrileno.main.AppConfig

class HealthCheckService(appConfig: AppConfig) {
  def healthCheck(): IO[AppConfig] = IO.pure(appConfig)
}
