package madrileno.healthcheck.gateways

import cats.effect.IO
import madrileno.healthcheck.gateways.dto.*
import sttp.capabilities.fs2.Fs2Streams
import sttp.client4.*
import sttp.client4.circe.*

class FingerprintingApiGateway(http: WebSocketStreamBackend[IO, Fs2Streams[IO]]) {
  def getIp: IO[FingerprintingApiResponse] = {
    basicRequest
      .get(uri"https://tls.peet.ws/api/all")
      .response(asJson[FingerprintingApiResponse])
      .send(http)
      .map(_.body)
      .rethrow
  }
}
