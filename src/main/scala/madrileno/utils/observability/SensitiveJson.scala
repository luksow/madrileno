package madrileno.utils.observability

import io.circe.{Json, JsonObject}

/** Field-level redaction of JSON that is about to be logged.
  *
  * The structure of the document is kept intact, only the values of sensitive fields are replaced by their [[Fingerprint]], so a logged
  * `{"refreshToken":"fp:3f9a1c2b"}` still tells you which token a 401 was about without carrying the token. Field names are matched
  * case-insensitively and ignoring `_` and `-`, so `refreshToken`, `refresh_token` and `Refresh-Token` all count.
  */
object SensitiveJson {

  /** Field names whose values are credentials, wherever they appear in a document.
    *
    * Extend this when a DTO gains a field that carries a secret. The guard that catches a forgotten entry is `SecretsStayOutOfLogsSpec`, which
    * asserts on the secret values themselves rather than on this list.
    */
  val SensitiveFields: Set[String] =
    Set("jwt", "refreshToken", "idToken", "firebaseJwtToken", "accessToken", "token", "password", "secret", "apiKey", "authorization")
      .map(normalize)

  def isSensitiveField(name: String): Boolean = SensitiveFields.contains(normalize(name))

  def redact(json: Json): Json =
    json.arrayOrObject(json, values => Json.fromValues(values.map(redact)), obj => Json.fromJsonObject(redactObject(obj)))

  private def redactObject(obj: JsonObject): JsonObject =
    JsonObject.fromIterable(obj.toIterable.map { case (name, value) =>
      if (isSensitiveField(name)) name -> redactValue(value) else name -> redact(value)
    })

  // `null` stays `null`: it tells the reader the field was absent, and there is nothing to protect.
  private def redactValue(value: Json): Json =
    if (value.isNull) value else Json.fromString(Fingerprint(value.asString.getOrElse(value.noSpaces)).value)

  private def normalize(name: String): String = name.toLowerCase.filterNot(c => c == '_' || c == '-')
}
