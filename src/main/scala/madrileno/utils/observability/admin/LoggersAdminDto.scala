package madrileno.utils.observability.admin

import ch.qos.logback.classic.Logger
import madrileno.utils.json.JsonProtocol.*
import org.slf4j.Logger as Slf4jLogger

final case class LoggerLevelDto(
  name: String,
  configuredLevel: Option[String],
  effectiveLevel: String)
    derives Encoder.AsObject,
      Decoder

object LoggerLevelDto {
  def apply(logger: Logger): LoggerLevelDto = {
    val name = if (logger.getName == Slf4jLogger.ROOT_LOGGER_NAME) "ROOT" else logger.getName
    LoggerLevelDto(name = name, configuredLevel = Option(logger.getLevel).map(_.toString), effectiveLevel = logger.getEffectiveLevel.toString)
  }
}

final case class SetLoggerLevelRequest(level: Option[String]) derives Decoder, Encoder.AsObject
