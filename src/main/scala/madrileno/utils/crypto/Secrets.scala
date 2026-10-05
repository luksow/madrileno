package madrileno.utils.crypto

import cats.effect.IO
import cats.effect.std.SecureRandom

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64

object RandomSecret {
  private val encoder = Base64.getUrlEncoder.withoutPadding

  def generate(bytes: Int)(using SecureRandom[IO]): IO[String] = {
    SecureRandom[IO].nextBytes(bytes).map(encoder.encodeToString)
  }
}

object Sha256 {
  private val encoder = Base64.getUrlEncoder.withoutPadding

  def base64Url(value: String): String = {
    val digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))
    encoder.encodeToString(digest)
  }
}
