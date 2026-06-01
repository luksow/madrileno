package madrileno.utils.observability.admin

import madrileno.utils.json.JsonProtocol.*

final case class DbStatusDto(config: DbConfigDto, connections: ConnectionsDto) derives Encoder.AsObject, Decoder

final case class DbConfigDto(
  host: String,
  port: Int,
  database: String,
  maxPoolSize: Int)
    derives Encoder.AsObject,
      Decoder

final case class ConnectionsDto(
  active: Long,
  idle: Long,
  idleInTransaction: Long,
  total: Long)
    derives Encoder.AsObject,
      Decoder
