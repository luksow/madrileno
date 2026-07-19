package madrileno.utils.events

import io.circe.{Codec, Json}

import scala.deriving.Mirror

trait EventCodec[E] {
  def encode(event: E): Json
  def decode(json: Json): Either[Throwable, E]
}

object EventCodec {
  def apply[E](using ec: EventCodec[E]): EventCodec[E] = ec

  inline def derived[E](using inline m: Mirror.Of[E]): EventCodec[E] = fromCodec(Codec.AsObject.derived[E])

  def fromCodec[E](codec: Codec[E]): EventCodec[E] = new CirceEventCodec[E](codec)

  private final class CirceEventCodec[E](codec: Codec[E]) extends EventCodec[E] {
    override def encode(event: E): Json                   = codec(event)
    override def decode(json: Json): Either[Throwable, E] = codec.decodeJson(json)
  }
}
