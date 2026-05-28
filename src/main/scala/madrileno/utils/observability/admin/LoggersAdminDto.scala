package madrileno.utils.observability.admin

import madrileno.utils.json.JsonProtocol.*

final case class LoggerLevelDto(
  name: String,
  configuredLevel: Option[String],
  effectiveLevel: String)
    derives Encoder.AsObject,
      Decoder

final case class SetLoggerLevelRequest(level: Option[String]) derives Decoder, Encoder.AsObject
