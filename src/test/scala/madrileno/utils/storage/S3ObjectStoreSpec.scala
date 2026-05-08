package madrileno.utils.storage

import cats.effect.testing.scalatest.AsyncIOSpec
import cats.effect.{IO, Resource}
import com.dimafeng.testcontainers.GenericContainer
import com.dimafeng.testcontainers.scalatest.TestContainerForAll
import fs2.Stream
import org.http4s.MediaType
import org.http4s.headers.`Content-Type`
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import org.testcontainers.containers.wait.strategy.Wait
import scodec.bits.ByteVector
import software.amazon.awssdk.auth.credentials.{AwsBasicCredentials, StaticCredentialsProvider}
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3AsyncClient
import software.amazon.awssdk.services.s3.model.CreateBucketRequest
import sttp.client4.basicRequest
import sttp.client4.httpurlconnection.HttpURLConnectionBackend
import sttp.model.Uri as SttpUri

import java.net.URI
import scala.concurrent.duration.DurationInt

class S3ObjectStoreSpec extends AsyncWordSpec with AsyncIOSpec with Matchers with TestContainerForAll {

  override val containerDef: GenericContainer.Def[GenericContainer] = GenericContainer.Def(
    dockerImage = "minio/minio:RELEASE.2024-11-07T00-52-20Z",
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

  private def createBucket(config: S3Config): IO[Unit] = {
    val clientResource = Resource.fromAutoCloseable(IO {
      S3AsyncClient
        .builder()
        .endpointOverride(new URI(config.endpoint))
        .region(Region.of(config.region))
        .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(config.accessKeyId, config.secretAccessKey)))
        .forcePathStyle(true)
        .build()
    })
    clientResource.use { client =>
      IO.fromCompletableFuture(IO(client.createBucket(CreateBucketRequest.builder().bucket(config.bucket).build()))).void
    }
  }

  private val plainText = `Content-Type`(MediaType.text.plain)
  private val ttl       = SignedUrlTtl(5.minutes)
  private val sttpSync  = HttpURLConnectionBackend()

  "S3ObjectStore against MinIO" should {
    "round-trip put/get/delete" in withContainers { container =>
      val config = configFor(container)
      createBucket(config) *>
        ObjectStoreRuntime
          .s3(config)
          .use { runtime =>
            val store = runtime.objectStore
            val key   = StorageKey("test/object.txt")
            val bytes = "hello s3".getBytes("UTF-8")
            for {
              written     <- store.put(key, plainText, Stream.emits(bytes))
              afterPut    <- store.get(key, ttl, Some("hello.txt"))
              _           <- store.delete(key)
              afterDelete <- store.get(key, ttl, Some("hello.txt"))
            } yield {
              written shouldBe bytes.length.toLong
              afterPut shouldBe a[ObjectStore.GetResult.Redirected]
              afterDelete shouldBe ObjectStore.GetResult.NotFound
            }
          }
          .timeout(60.seconds)
    }

    "head returns size + content-type for an existing object and None after delete" in withContainers { container =>
      val config = configFor(container)
      createBucket(config) *>
        ObjectStoreRuntime
          .s3(config)
          .use { runtime =>
            val store = runtime.objectStore
            val key   = StorageKey("test/head.txt")
            val bytes = "head test".getBytes("UTF-8")
            for {
              _             <- store.put(key, plainText, Stream.emits(bytes))
              statBeforeDel <- store.head(key)
              _             <- store.delete(key)
              statAfterDel  <- store.head(key)
            } yield {
              statBeforeDel shouldBe Some(ObjectStat(bytes.length.toLong, plainText))
              statAfterDel shouldBe None
            }
          }
          .timeout(60.seconds)
    }

    "fetchBytes returns the stored bytes" in withContainers { container =>
      val config = configFor(container)
      createBucket(config) *>
        ObjectStoreRuntime
          .s3(config)
          .use { runtime =>
            val store = runtime.objectStore
            val key   = StorageKey("test/fetch.bin")
            val bytes = (0 to 255).map(_.toByte).toArray
            for {
              _       <- store.put(key, plainText, Stream.emits(bytes))
              fetched <- store.fetchBytes(key)
            } yield fetched shouldBe ByteVector(bytes)
          }
          .timeout(60.seconds)
    }

    "presignPut produces a URL that accepts a matching PUT" in withContainers { container =>
      val config = configFor(container)
      createBucket(config) *>
        ObjectStoreRuntime
          .s3(config)
          .use { runtime =>
            val store = runtime.objectStore
            val key   = StorageKey("test/upload.txt")
            val bytes = "uploaded directly".getBytes("UTF-8")
            for {
              uploadUrl <- store.presignPut(key, ttl, plainText, bytes.length.toLong)
              response <- IO.blocking {
                            basicRequest
                              .put(SttpUri(new URI(uploadUrl.renderString)).queryValueSegmentsEncoding(SttpUri.QuerySegmentEncoding.All))
                              .body(bytes)
                              .contentType("text/plain")
                              .send(sttpSync)
                          }
              stat <- store.head(key)
            } yield {
              response.code.code shouldBe 200
              stat shouldBe Some(ObjectStat(bytes.length.toLong, plainText))
            }
          }
          .timeout(60.seconds)
    }
  }
}
