package madrileno.main

import io.opentelemetry.api.common.{AttributeKey, Attributes}
import io.opentelemetry.sdk.resources.Resource as OtelResource
import org.scalatest.funspec.AnyFunSpec
import org.scalatest.matchers.should.Matchers

class OtelResourceSpec extends AnyFunSpec with Matchers {

  describe("Main.mergeAppResource") {
    it("derives deployment.environment and service.version from app config, overriding env-provided values") {
      val appConfig = AppConfig(name = "svc", environment = Environment.Prod, version = "1.2.3")
      val base      = OtelResource.create(Attributes.builder().put("deployment.environment", "stale").build())

      val merged = Main.mergeAppResource(appConfig, base)

      merged.getAttribute(AttributeKey.stringKey("deployment.environment")) shouldBe "prod"
      merged.getAttribute(AttributeKey.stringKey("service.version")) shouldBe "1.2.3"
    }
  }
}
