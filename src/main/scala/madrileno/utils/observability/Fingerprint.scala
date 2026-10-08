package madrileno.utils.observability

import pl.iterators.kebs.core.macros.ValueClassLike
import pureconfig.ConfigReader

import java.nio.charset.StandardCharsets.UTF_8
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

opaque type Fingerprint = String

object Fingerprint {
  private[observability] def apply(value: String): Fingerprint = value

  extension (fingerprint: Fingerprint) {
    def value: String = fingerprint
  }
}

class Fingerprinter(config: Fingerprinter.Config) {
  private val key = new SecretKeySpec(config.fingerprintSecret.getBytes(UTF_8), Fingerprinter.Algorithm)

  def apply[T](secret: T)(using valueClassLike: ValueClassLike[T, String]): Fingerprint = apply(valueClassLike.unapply(secret))

  def apply(secret: String): Fingerprint = {
    val mac = Mac.getInstance(Fingerprinter.Algorithm)
    mac.init(key)
    val digest = mac.doFinal(secret.getBytes(UTF_8))
    Fingerprint(Fingerprinter.Prefix + digest.take(Fingerprinter.Bytes).map(byte => f"$byte%02x").mkString)
  }
}

object Fingerprinter {
  final case class Config(fingerprintSecret: String) derives ConfigReader

  private val Algorithm = "HmacSHA256"
  private val Prefix    = "fp:"
  private val Bytes     = 4
}
