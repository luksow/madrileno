package madrileno.utils.crypto

import cats.effect.IO
import cats.effect.std.SecureRandom

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.spec.{GCMParameterSpec, SecretKeySpec}
import javax.crypto.{Cipher, Mac}
import scala.util.Try

object Base64Url {
  private val encoder = Base64.getUrlEncoder.withoutPadding
  private val decoder = Base64.getUrlDecoder

  def encode(bytes: Array[Byte]): String = encoder.encodeToString(bytes)

  def decode(value: String): Array[Byte] = decoder.decode(value)
}

object Hmac {
  private val Algorithm = "HmacSHA256"

  def sha256(key: Array[Byte], data: Array[Byte]): Array[Byte] = {
    val mac = Mac.getInstance(Algorithm)
    mac.init(new SecretKeySpec(key, Algorithm))
    mac.doFinal(data)
  }
}

object SecretBox {
  private val Transformation = "AES/GCM/NoPadding"
  private val NonceLength    = 12
  private val TagBits        = 128

  def seal(key: Array[Byte], plaintext: String)(using SecureRandom[IO]): IO[String] = {
    SecureRandom[IO].nextBytes(NonceLength).map { nonce =>
      val cipher = Cipher.getInstance(Transformation)
      cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(TagBits, nonce))
      Base64Url.encode(nonce ++ cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8)))
    }
  }

  def open(key: Array[Byte], box: String): Option[String] = {
    Try {
      val (nonce, ciphertext) = Base64Url.decode(box).splitAt(NonceLength)
      val cipher              = Cipher.getInstance(Transformation)
      cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(TagBits, nonce))
      new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8)
    }.toOption
  }
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
