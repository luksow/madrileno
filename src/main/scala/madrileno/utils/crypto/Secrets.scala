package madrileno.utils.crypto

import cats.effect.IO
import cats.effect.std.SecureRandom

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64

object Base64Url {
  private val encoder = Base64.getUrlEncoder.withoutPadding

  def encode(bytes: Array[Byte]): String = encoder.encodeToString(bytes)
}

object RandomSecret {
  def generate(bytes: Int)(using SecureRandom[IO]): IO[String] = {
    SecureRandom[IO].nextBytes(bytes).map(Base64Url.encode)
  }
}

object Sha256 {
  def base64Url(value: String): String = {
    Base64Url.encode(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)))
  }
}
