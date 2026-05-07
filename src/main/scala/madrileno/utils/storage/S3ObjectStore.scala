package madrileno.utils.storage

import cats.effect.IO
import fs2.Stream
import org.http4s.headers.Location
import org.http4s.{Headers, Response, Status, Uri}
import scodec.bits.ByteVector
import software.amazon.awssdk.core.async.{AsyncRequestBody, AsyncResponseTransformer}
import software.amazon.awssdk.services.s3.S3AsyncClient
import software.amazon.awssdk.services.s3.model.{DeleteObjectRequest, GetObjectRequest, GetObjectResponse, PutObjectRequest}
import software.amazon.awssdk.services.s3.presigner.S3Presigner
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest

import java.time.Duration as JDuration
import scala.concurrent.duration.FiniteDuration

final case class S3Config(
  endpoint: String,
  region: String,
  bucket: String,
  accessKeyId: String,
  secretAccessKey: String)

class S3ObjectStore(
  client: S3AsyncClient,
  presigner: S3Presigner,
  bucket: String)
    extends ObjectStore {

  override def put(
    key: StorageKey,
    contentType: String,
    sizeBytes: Long,
    content: Stream[IO, Byte]
  ): IO[Unit] = {
    val _ = sizeBytes
    content.compile.to(ByteVector).flatMap { bytes =>
      val request = PutObjectRequest.builder().bucket(bucket).key(key.unwrap).contentType(contentType).contentLength(bytes.length).build()
      val body    = AsyncRequestBody.fromBytes(bytes.toArray)
      IO.fromCompletableFuture(IO(client.putObject(request, body))).void
    }
  }

  override def serve(key: StorageKey, ttl: FiniteDuration): IO[Response[IO]] = IO.blocking {
    val getRequest = GetObjectRequest.builder().bucket(bucket).key(key.unwrap).build()
    val signed = presigner.presignGetObject(
      GetObjectPresignRequest.builder().signatureDuration(JDuration.ofSeconds(ttl.toSeconds)).getObjectRequest(getRequest).build()
    )
    Response[IO](Status.SeeOther, headers = Headers(Location(Uri.unsafeFromString(signed.url().toString))))
  }

  override def delete(key: StorageKey): IO[Unit] =
    IO.fromCompletableFuture(IO(client.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key.unwrap).build()))).void

  def stream(key: StorageKey): Stream[IO, Byte] = {
    val request = GetObjectRequest.builder().bucket(bucket).key(key.unwrap).build()
    Stream.eval(IO.fromCompletableFuture(IO(client.getObject(request, AsyncResponseTransformer.toBytes[GetObjectResponse])))).flatMap { bytes =>
      Stream.chunk(fs2.Chunk.array(bytes.asByteArray()))
    }
  }
}
