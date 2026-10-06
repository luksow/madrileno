package madrileno.utils.observability

import java.nio.charset.StandardCharsets.UTF_8
import java.security.MessageDigest

/** A short, stable, non-reversible handle for a secret, meant for log lines and telemetry.
  *
  * Two log lines that mention the same JWT or refresh token carry the same fingerprint, so an operator can still follow a
  * credential through a trace, but the fingerprint reveals nothing about the credential itself: it is the first 32 bits of a
  * SHA-256, and every credential we fingerprint has far more entropy than that.
  */
opaque type Fingerprint = String

object Fingerprint {
  private val Prefix    = "fp:"
  private val HexDigits = 8

  def apply(secret: String): Fingerprint = {
    val digest = MessageDigest.getInstance("SHA-256").digest(secret.getBytes(UTF_8))
    Prefix + digest.take(HexDigits / 2).map(byte => f"$byte%02x").mkString
  }

  extension (fingerprint: Fingerprint) {
    def value: String = fingerprint
  }
}
