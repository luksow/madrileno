package madrileno.utils.storage

import cats.effect.IO
import cats.effect.testing.scalatest.AsyncIOSpec
import com.dimafeng.testcontainers.GenericContainer
import com.dimafeng.testcontainers.scalatest.TestContainerForAll
import fs2.Stream
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import org.testcontainers.containers.wait.strategy.Wait

import scala.concurrent.duration.DurationInt

class S3ObjectStoreSpec extends AsyncWordSpec with AsyncIOSpec with Matchers with TestContainerForAll {

  override val containerDef: GenericContainer.Def[GenericContainer] = GenericContainer.Def(
    dockerImage = "minio/minio:latest",
    exposedPorts = Seq(9000),
    env = Map("MINIO_ROOT_USER" -> "minioadmin", "MINIO_ROOT_PASSWORD" -> "minioadmin"),
    command = Seq("server", "/data"),
    waitStrategy = Wait.forHttp("/minio/health/live").forPort(9000)
  )

  private def configFor(container: GenericContainer): S3Config = S3Config(
    endpoint = s"http://${container.host}:${container.mappedPort(9000)}",
    region = "us-east-1",
    bucket = s"test-${java.util.UUID.randomUUID()}",
    accessKeyId = "minioadmin",
    secretAccessKey = "minioadmin"
  )

  "S3ObjectStore against MinIO" should {
    "round-trip a put/serve/delete" in withContainers { container =>
      ObjectStoreRuntime
        .s3(configFor(container))
        .use { runtime =>
          val store = runtime.objectStore
          val key   = StorageKey("test/object.txt")
          val bytes = "hello s3".getBytes("UTF-8")
          for {
            _      <- store.put(key, "text/plain", bytes.length.toLong, Stream.emits(bytes))
            served <- store.serve(key, 5.minutes)
            _ = served.status.code shouldBe 303 // SeeOther — redirect to presigned URL
            _ <- store.delete(key)
          } yield succeed
        }
        .timeout(60.seconds)
    }

    "creates the bucket if it doesn't exist" in withContainers { container =>
      val config = configFor(container)
      ObjectStoreRuntime
        .s3(config)
        .use { runtime =>
          runtime.objectStore
            .put(StorageKey("created/object.txt"), "text/plain", 4L, Stream.emits("data".getBytes("UTF-8")))
            .as(succeed)
        }
        .timeout(60.seconds)
    }
  }
}
