package madrileno.utils.storage

import cats.effect.{IO, Resource}
import fs2.io.file.Path as FsPath
import software.amazon.awssdk.auth.credentials.{AwsBasicCredentials, StaticCredentialsProvider}
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.model.{
  BucketLocationConstraint,
  CreateBucketConfiguration,
  CreateBucketRequest,
  HeadBucketRequest,
  NoSuchBucketException,
  S3Exception
}
import software.amazon.awssdk.services.s3.presigner.S3Presigner
import software.amazon.awssdk.services.s3.{S3AsyncClient, S3Configuration}

import java.net.URI

trait ObjectStoreRuntime {
  def objectStore: ObjectStore
}

object ObjectStoreRuntime {

  def disk(root: FsPath): ObjectStoreRuntime = new ObjectStoreRuntime {
    override val objectStore: ObjectStore = new DiskObjectStore(root)
  }

  def s3(config: S3Config): Resource[IO, ObjectStoreRuntime] =
    for {
      client <- Resource.fromAutoCloseable(IO {
                  S3AsyncClient
                    .builder()
                    .endpointOverride(new URI(config.endpoint))
                    .region(Region.of(config.region))
                    .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(config.accessKeyId, config.secretAccessKey)))
                    .forcePathStyle(true)
                    .build()
                })
      presigner <- Resource.fromAutoCloseable(IO {
                     S3Presigner
                       .builder()
                       .endpointOverride(new URI(config.endpoint))
                       .region(Region.of(config.region))
                       .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(config.accessKeyId, config.secretAccessKey)))
                       .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
                       .build()
                   })
      _ <- Resource.eval(ensureBucket(client, config.bucket, config.region))
    } yield new ObjectStoreRuntime {
      override val objectStore: ObjectStore = new S3ObjectStore(client, presigner, config.bucket)
    }

  private def ensureBucket(
    client: S3AsyncClient,
    bucket: String,
    region: String
  ): IO[Unit] = {
    val builder = CreateBucketRequest.builder().bucket(bucket)
    val createRequest =
      if (region == "us-east-1") builder.build()
      else
        builder
          .createBucketConfiguration(CreateBucketConfiguration.builder().locationConstraint(BucketLocationConstraint.fromValue(region)).build())
          .build()
    val create = IO.fromCompletableFuture(IO(client.createBucket(createRequest))).void
    val head   = IO.fromCompletableFuture(IO(client.headBucket(HeadBucketRequest.builder().bucket(bucket).build()))).void
    head.recoverWith {
      case _: NoSuchBucketException                => create
      case e: S3Exception if e.statusCode() == 404 => create
    }
  }
}
