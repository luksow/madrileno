package madrileno.utils.http

enum ApiVersion(val urlSegment: String) {
  case V1 extends ApiVersion("v1")
}
