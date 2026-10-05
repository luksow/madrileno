package madrileno.utils.crypto

import cats.effect.IO

import java.nio.charset.StandardCharsets
import java.security.{MessageDigest, SecureRandom}
import java.util.Base64

object RandomSecret {
  private val encoder = Base64.getUrlEncoder.withoutPadding

  def generate(bytes: Int): IO[String] = IO {
    val buffer = new Array[Byte](bytes)
    new SecureRandom().nextBytes(buffer)
    encoder.encodeToString(buffer)
  }
}

object Sha256 {
  private val encoder = Base64.getUrlEncoder.withoutPadding

  def base64Url(value: String): String = {
    val digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))
    encoder.encodeToString(digest)
  }
}
