package madrileno.utils.storage

import java.net.URLEncoder
import java.nio.charset.StandardCharsets

object ContentDispositions {

  def attachment(fileName: String): String = {
    val sanitized = fileName.filter(c => c >= 0x20 && c != 0x7f)
    val asciiSafe = sanitized.filter(c => c < 0x80 && c != '"' && c != '\\')
    val rfc5987   = URLEncoder.encode(sanitized, StandardCharsets.UTF_8).replace("+", "%20").replace("*", "%2A")
    s"""attachment; filename="$asciiSafe"; filename*=UTF-8''$rfc5987"""
  }
}
