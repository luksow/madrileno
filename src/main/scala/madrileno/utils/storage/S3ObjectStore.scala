package madrileno.utils.storage

import cats.effect.IO
import fs2.Stream
import org.http4s.headers.`Content-Type`
import org.http4s.{Header, Uri}
import pureconfig.ConfigReader
import scodec.bits.ByteVector
import software.amazon.awssdk.core.async.AsyncRequestBody
import software.amazon.awssdk.services.s3.S3AsyncClient
import software.amazon.awssdk.services.s3.model.{
  DeleteObjectRequest,
  GetObjectRequest,
  HeadObjectRequest,
  NoSuchKeyException,
  PutObjectRequest,
  S3Exception
}
import software.amazon.awssdk.services.s3.presigner.S3Presigner
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest

final case class S3Config(
  endpoint: String,
  region: String,
  bucket: String,
  accessKeyId: String,
  secretAccessKey: String)
    derives ConfigReader

class S3ObjectStore(
  client: S3AsyncClient,
  presigner: S3Presigner,
  bucket: String)
    extends ObjectStore {

  // Buffered upload: bounded by `http.max-request-size` (10 MiB by default in application.conf).
  // S3's putObject requires a known Content-Length; for streamed uploads of larger files, switch
  // to an S3TransferManager-based multipart implementation.
  override def put(
    key: StorageKey,
    contentType: `Content-Type`,
    body: Stream[IO, Byte]
  ): IO[Long] = {
    val ctValue = Header[`Content-Type`].value(contentType)
    body.compile.to(ByteVector).flatMap { bytes =>
      val request = PutObjectRequest.builder().bucket(bucket).key(key.render).contentType(ctValue).contentLength(bytes.length).build()
      IO.fromCompletableFuture(IO(client.putObject(request, AsyncRequestBody.fromBytes(bytes.toArray)))).void.as(bytes.length)
    }
  }

  override def get(
    key: StorageKey,
    ttl: SignedUrlTtl,
    fileName: Option[String]
  ): IO[ObjectStore.GetResult] = {
    val head = IO.fromCompletableFuture(IO(client.headObject(HeadObjectRequest.builder().bucket(bucket).key(key.render).build()))).void
    val presign = IO.blocking {
      val builder  = GetObjectRequest.builder().bucket(bucket).key(key.render)
      val withDisp = fileName.fold(builder)(name => builder.responseContentDisposition(ContentDispositions.attachment(name)))
      val signed =
        presigner.presignGetObject(GetObjectPresignRequest.builder().signatureDuration(ttl.asJavaDuration).getObjectRequest(withDisp.build()).build())
      ObjectStore.GetResult.Redirected(Uri.unsafeFromString(signed.url().toString))
    }
    head.flatMap(_ => presign).recover {
      case _: NoSuchKeyException                   => ObjectStore.GetResult.NotFound
      case e: S3Exception if e.statusCode() == 404 => ObjectStore.GetResult.NotFound
    }
  }

  override def delete(key: StorageKey): IO[Unit] =
    IO.fromCompletableFuture(IO(client.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key.render).build()))).void
}
