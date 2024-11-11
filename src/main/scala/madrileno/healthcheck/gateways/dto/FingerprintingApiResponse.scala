package madrileno.healthcheck.gateways.dto

import io.circe.Codec

case class FingerprintingApiResponse(ip: String) derives Codec.AsObject
