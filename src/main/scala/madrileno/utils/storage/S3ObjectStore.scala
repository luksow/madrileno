package madrileno.utils.storage

import cats.effect.IO
import fs2.Stream
import org.http4s.headers.{`Content-Disposition`, `Content-Type`}
import org.http4s.{Header, Headers, MediaType, Uri}
import org.typelevel.ci.*
import pureconfig.ConfigReader
import scodec.bits.ByteVector
import software.amazon.awssdk.core.async.{AsyncRequestBody, AsyncResponseTransformer}
import software.amazon.awssdk.services.s3.S3AsyncClient
import software.amazon.awssdk.services.s3.model.{
  DeleteObjectRequest,
  GetObjectRequest,
  GetObjectResponse,
  HeadObjectRequest,
  NoSuchKeyException,
  PutObjectRequest,
  S3Exception
}
import software.amazon.awssdk.services.s3.presigner.S3Presigner
import software.amazon.awssdk.services.s3.presigner.model.{GetObjectPresignRequest, PutObjectPresignRequest}

import scala.jdk.CollectionConverters.*

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
  bucket: String,
  maxFetchBytes: Long)
    extends ObjectStore {

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
    val headIO = IO.fromCompletableFuture(IO(client.headObject(HeadObjectRequest.builder().bucket(bucket).key(key.render).build()))).void
    val presign = IO.blocking {
      val builder = GetObjectRequest.builder().bucket(bucket).key(key.render)
      val withDisp = fileName.fold(builder) { name =>
        val cd = `Content-Disposition`("attachment", Map(ci"filename" -> name))
        builder.responseContentDisposition(Header[`Content-Disposition`].value(cd))
      }
      val signed =
        presigner.presignGetObject(GetObjectPresignRequest.builder().signatureDuration(ttl.asJavaDuration).getObjectRequest(withDisp.build()).build())
      ObjectStore.GetResult.Redirected(Uri.unsafeFromString(signed.url().toString))
    }
    headIO.flatMap(_ => presign).recover {
      case _: NoSuchKeyException                   => ObjectStore.GetResult.NotFound
      case e: S3Exception if e.statusCode() == 404 => ObjectStore.GetResult.NotFound
    }
  }

  override def delete(key: StorageKey): IO[Unit] =
    IO.fromCompletableFuture(IO(client.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key.render).build()))).void

  override def presignPut(
    key: StorageKey,
    ttl: SignedUrlTtl,
    contentType: `Content-Type`,
    contentLength: Long
  ): IO[PresignedPut] = IO.blocking {
    val ctValue = Header[`Content-Type`].value(contentType)
    val request = PutObjectRequest.builder().bucket(bucket).key(key.render).contentType(ctValue).contentLength(contentLength).build()
    val signed = presigner.presignPutObject(PutObjectPresignRequest.builder().signatureDuration(ttl.asJavaDuration).putObjectRequest(request).build())
    val raws = signed
      .signedHeaders()
      .asScala
      .iterator
      .flatMap { case (name, values) =>
        values.asScala.iterator.map(value => Header.Raw(CIString(name), value))
      }
      .toList
    PresignedPut(Uri.unsafeFromString(signed.url().toString), Headers(raws))
  }

  override def head(key: StorageKey): IO[Option[ObjectStat]] = {
    val request = HeadObjectRequest.builder().bucket(bucket).key(key.render).build()
    IO.fromCompletableFuture(IO(client.headObject(request)))
      .map { response =>
        val ct =
          Option(response.contentType()).flatMap(`Content-Type`.parse(_).toOption).getOrElse(`Content-Type`(MediaType.application.`octet-stream`))
        Some(ObjectStat(response.contentLength(), ct))
      }
      .recover {
        case _: NoSuchKeyException                   => None
        case e: S3Exception if e.statusCode() == 404 => None
      }
  }

  override def fetchBytes(key: StorageKey): IO[Option[ByteVector]] =
    head(key).flatMap {
      case None => IO.pure(None)
      case Some(stat) if stat.sizeBytes > maxFetchBytes =>
        IO.raiseError(ObjectTooLarge(key, stat.sizeBytes, maxFetchBytes))
      case Some(_) =>
        val request = GetObjectRequest.builder().bucket(bucket).key(key.render).build()
        IO.fromCompletableFuture(IO(client.getObject(request, AsyncResponseTransformer.toBytes[GetObjectResponse])))
          .map(bytes => Some(ByteVector(bytes.asByteArray())))
    }
}
