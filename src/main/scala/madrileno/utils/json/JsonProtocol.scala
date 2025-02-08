package madrileno.utils.json

import org.http4s.circe.{CirceEntityDecoder, CirceEntityEncoder}
import pl.iterators.kebs.circe.enums.KebsCirceEnums
import pl.iterators.kebs.instances.{NetInstances, TimeInstances, UtilInstances}

trait JsonProtocol extends CirceEntityDecoder with CirceEntityEncoder with KebsCirceEnums with TimeInstances with UtilInstances with NetInstances {
  export pl.iterators.kebs.circe.flatDecoder
  export pl.iterators.kebs.circe.flatEncoder
  export pl.iterators.kebs.circe.instanceConverterEncoder
  export pl.iterators.kebs.circe.instanceConverterDecoder

  type Encoder[A] = io.circe.Encoder[A]
  val Encoder: io.circe.Encoder.type = io.circe.Encoder

  type Decoder[A] = io.circe.Decoder[A]
  val Decoder: io.circe.Decoder.type = io.circe.Decoder
}

object JsonProtocol extends JsonProtocol
