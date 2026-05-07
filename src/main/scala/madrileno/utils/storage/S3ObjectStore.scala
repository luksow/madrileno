package madrileno.utils.storage

import cats.effect.{IO, Ref}
import fs2.Stream
import fs2.interop.reactivestreams.*
import org.http4s.headers.`Content-Type`
import org.http4s.{Header, Uri}
import pureconfig.ConfigReader
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

import java.net.URLEncoder
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.time.Duration as JDuration

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

  override def put(
    key: StorageKey,
    metadata: ObjectMetadata,
    body: Stream[IO, Byte]
  ): IO[Long] = {
    val ctValue = Header[`Content-Type`].value(metadata.contentType)
    Ref.of[IO, Long](0L).flatMap { counter =>
      val counted = body.chunks.evalTap(c => counter.update(_ + c.size))
      counted.map(c => ByteBuffer.wrap(c.toArray)).toUnicastPublisher.use { publisher =>
        val builder = PutObjectRequest.builder().bucket(bucket).key(key.render).contentType(ctValue)
        val request = if (metadata.sizeBytes > 0) builder.contentLength(metadata.sizeBytes).build() else builder.build()
        IO.fromCompletableFuture(IO(client.putObject(request, AsyncRequestBody.fromPublisher(publisher)))).void
      } *> counter.get
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
      val withDisp = fileName.fold(builder)(name => builder.responseContentDisposition(contentDisposition(name)))
      val signed = presigner.presignGetObject(
        GetObjectPresignRequest.builder().signatureDuration(JDuration.ofSeconds(ttl.unwrap.toSeconds)).getObjectRequest(withDisp.build()).build()
      )
      ObjectStore.GetResult.Redirected(Uri.unsafeFromString(signed.url().toString))
    }
    head.flatMap(_ => presign).recover {
      case _: NoSuchKeyException                   => ObjectStore.GetResult.NotFound
      case e: S3Exception if e.statusCode() == 404 => ObjectStore.GetResult.NotFound
    }
  }

  override def delete(key: StorageKey): IO[Unit] =
    IO.fromCompletableFuture(IO(client.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key.render).build()))).void

  private def contentDisposition(name: String): String = {
    val sanitized = name.filter(c => c >= 0x20 && c != 0x7f)
    val asciiSafe = sanitized.filter(c => c < 0x80 && c != '"' && c != '\\')
    val rfc5987   = URLEncoder.encode(sanitized, StandardCharsets.UTF_8).replace("+", "%20")
    s"""attachment; filename="$asciiSafe"; filename*=UTF-8''$rfc5987"""
  }
}
