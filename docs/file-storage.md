# File storage

The codebase ships an object-storage abstraction with two backends — local disk for the no-deps demo, and any S3-compatible service (AWS S3, MinIO, R2, etc.) for production. Wine-auction images are the worked example: see `madrileno.auction` for an end-to-end module that uses it.

## What you get

```
src/main/scala/madrileno/utils/storage/
  ObjectStore.scala        # the trait + StorageKey + ObjectMetadata + GetResult ADT
  DiskObjectStore.scala    # local filesystem impl
  S3ObjectStore.scala      # AWS SDK v2 impl + S3Config
  ObjectStoreRuntime.scala # disk(...) / s3(...) factories (s3 is Resource-shaped)
  SignedUrlTtl.scala       # opaque FiniteDuration; per-module knob
  StorageConfig.scala      # pureconfig wrapper for S3Config
```

The contract is `put(key, metadata, body) -> bytes written`, `get(key, ttl, fileName) -> Streamed | Redirected | NotFound`, `delete(key)`. `put` returns the actual byte count, so callers don't have to count themselves.

## Quick start (dev)

`docker compose up -d` brings up MinIO on `127.0.0.1:59000` (S3 API) and `127.0.0.1:59001` (console — login `minioadmin` / `minioadmin`). A `minio-init` sidecar runs `mc mb --ignore-existing local/madrileno` once the server is healthy, so the bucket is there on first boot.

`.env.sample` is wired against that container:

```
S3_ENDPOINT=http://localhost:59000
S3_REGION=us-east-1
S3_BUCKET=madrileno
S3_ACCESS_KEY_ID=minioadmin
S3_SECRET_ACCESS_KEY=minioadmin
```

`Main.scala` always wires the S3 backend:

```scala
storageConfig      <- Resource.eval(IO.delay(config.at("storage").loadOrThrow[StorageConfig]))
objectStoreRuntime <- ObjectStoreRuntime.s3(storageConfig.objectStorage)
```

If you want the disk backend instead (e.g. zero-deps local dev or running tests outside Testcontainers), swap that line for `Resource.pure(ObjectStoreRuntime.disk(FsPath("./uploads")))`. There's intentionally no config-driven dispatch — the choice lives in `Main`, the same way `CacheRuntime.scaffeine` and `RateLimiterRuntime.scaffeine()` do.

## The abstraction

```scala
trait ObjectStore {
  def put(key: StorageKey, metadata: ObjectMetadata, body: Stream[IO, Byte]): IO[Long]
  def get(key: StorageKey, ttl: SignedUrlTtl, fileName: Option[String]): IO[ObjectStore.GetResult]
  def delete(key: StorageKey): IO[Unit]
}

object ObjectStore {
  enum GetResult {
    case Streamed(contentType: `Content-Type`, fileName: Option[String], body: Stream[IO, Byte])
    case Redirected(url: Uri)
    case NotFound
  }
}
```

A few decisions worth flagging:

- **`get` returns an ADT, not `Response[IO]`.** Each backend picks the access mode that fits. `S3ObjectStore` returns `Redirected` (a presigned URL — the client downloads from S3 directly, the API never proxies bytes). `DiskObjectStore` returns `Streamed` (we have the bytes locally; cheaper than redirecting to ourselves). The router maps either result to an http4s response.
- **`fileName` is per-fetch, not stored on the object.** S3 bakes it into the presign via `responseContentDisposition`; disk passes it through to the router which sets the header. That's why the storage layer doesn't persist filenames — the *caller* knows what to name the download (e.g. the auction's `auction_image.file_name` column).
- **`put` streams.** S3 uses fs2 reactive-streams interop (`toUnicastPublisher`) to feed the AWS SDK without buffering. Disk uses `Files[IO].writeAll`. Both report the actual byte count.
- **`StorageKey.render` over `.unwrap`.** A small extension method gives the storage code a domain-aware name when it has to surface the raw string for SDK calls.

## Wiring it into a module

`ApplicationLoader` exposes a single `objectStore: ObjectStore` for the whole app. Modules just declare it as an abstract `val` and use it.

`AuctionModule.scala`:

```scala
trait AuctionModule extends RouteProvider with AuthRouteProvider /* ... */ {
  val objectStore: ObjectStore
  protected lazy val signedUrlTtl: SignedUrlTtl = SignedUrlTtl(5.minutes)

  private val auctionImageService = wire[AuctionImageService]
  // ...
}
```

`signedUrlTtl` is per-module on purpose. Different file types want different presign lifetimes — auction images can be longer-lived than, say, one-time download links — and macwire picks the right `SignedUrlTtl` by type without ambiguity.

## Storing a file

The pattern is **upload first, persist second, clean up storage on persist failure.** Anything else either leaves a row pointing at a missing object (worse: `404` for a row that exists) or leaves an orphan blob with no row.

`AuctionImageService.attachImage` (simplified):

```scala
for {
  id         <- IdGenerator.generateId(AuctionImageId)
  now        <- Clock[IO].realTimeInstant
  key         = StorageKey(s"auctions/$auctionId/images/$id")
  actualSize <- objectStore.put(key, ObjectMetadata(contentType, sizeBytesHint), content)
  result     <- persistAttached(...).attempt.flatMap {
                  case Right(AttachImageResult.Attached(image)) => IO.pure(AttachImageResult.Attached(image))
                  case Right(other)                             => objectStore.delete(key).attempt.as(other)
                  case Left(t)                                  => objectStore.delete(key).attempt *> IO.raiseError(t)
                }
} yield result
```

The persist runs inside `transactor.inTransaction` and locks the auction row via `auctionRepository.findForUpdate(auctionId)` — this serializes concurrent uploads to the same auction so `nextPosition` doesn't race.

`sizeBytesHint` is what the multipart `Content-Length` header claimed (often unreliable / `0`); `actualSize` is what we actually wrote. The DB row uses the actual size.

## Serving a file

`AuctionImageService.serveImage` returns `Option[ObjectStore.GetResult]` — `None` when the row is missing or the image belongs to a different auction; otherwise the storage backend's result, which the router pattern-matches:

```scala
auctionImageService.serveImage(auctionId, imageId).map {
  case None | Some(ObjectStore.GetResult.NotFound) => error(NotFound, "image-not-found", "Image not found")
  case Some(ObjectStore.GetResult.Redirected(url)) => Response[IO](Status.SeeOther, headers = Headers(Location(url)))
  case Some(ObjectStore.GetResult.Streamed(ct, fileName, body)) =>
    val baseHeaders = Headers(`Content-Type`(ct.mediaType, ct.charset))
    val headers     = fileName.fold(baseHeaders)(name => baseHeaders.put(`Content-Disposition`("attachment", Map(CIString("filename") -> name))))
    Response[IO](Status.Ok, headers = headers, body = body)
}
```

For S3, `S3ObjectStore.get` issues a `HeadObject` before presigning so a missing key surfaces as `GetResult.NotFound` (instead of redirecting to a presigned URL that S3 will reject with 404). It costs one extra round-trip; in exchange the API contract holds for both backends.

## Position invariants and reordering

The `auction_image` table has a partial unique index on `(auction_id, position) WHERE deleted_at IS NULL`. That keeps positions strict at the DB level — concurrent attaches that race on `nextPosition` get one winner per position; the loser fails on the constraint and the upload-first/persist-second pattern cleans up its blob.

Reordering can't update positions row-by-row — swapping `[A=0, B=1]` to `[A=1, B=0]` would hit the constraint mid-transaction. `AuctionImageRepository.bulkSetPositions` does a two-phase update inside a single transaction:

1. Move every targeted row to a unique negative slot (`-(idx + 1)` per loop index) — the partial unique allows it because no other row uses negatives.
2. Move every targeted row to its final position.

The `ImagePosition` opaque type still rejects negatives in the domain — the negatives only exist as raw integers inside the repo helper, never as domain values.

## Production deployment

- **Pre-create buckets out-of-band.** `ObjectStoreRuntime.s3` does not auto-create. Use Terraform/CDK in cloud, `mc mb` in dev (already wired into `minio-init`), or whatever your provider offers.
- **IAM on AWS.** The app needs `s3:PutObject`, `s3:GetObject`, `s3:DeleteObject` on the bucket prefix. Presigned URLs inherit the signer's permissions; the credentials in `.env` need `GetObject` for downloads to work after the redirect.
- **Region constraints.** AWS S3 requires `LocationConstraint` for any region other than `us-east-1`. Bucket creation isn't done by the app, so this isn't a runtime concern — but it's something to remember in your IaC.
- **Presigned URL TTL.** Set per module via `SignedUrlTtl`. Short TTLs are good for security; long TTLs are CDN-cache-friendly. Five minutes is the auction-images default; raise or lower for your file type.
- **`max-request-size` is the upload ceiling.** `application.conf` sets `max-request-size = 10 MiB` so multipart uploads aren't rejected by the global `EntityLimiter`. If you need larger files, bump that — or split into chunked / resumable uploads, which is out of scope for this template.

## Testing

- **`TestObjectStoreRuntime.inMemory`** — a `Ref`-backed in-memory `ObjectStore`. Used by `TestApplicationLoader` so route specs don't need MinIO. `get` returns `Streamed` (no presigned URL needed).
- **`S3ObjectStoreSpec`** — runs against a MinIO Testcontainer pinned to `RELEASE.2024-11-07T00-52-20Z`. Creates the test bucket explicitly (the app no longer auto-creates) using a `Resource.fromAutoCloseable` S3 client so nothing leaks.
- **`AuctionImageServiceSpec`** — exercises the service against real Postgres + the in-memory store. Covers attach success/positioning/owner checks, detach, reorder swap (which exercises the two-phase repo helper), and serve cross-auction guards.
- **`AuctionImageRouterSpec`** — full baklava DSL against `TestApplicationLoader`. Uses `pl.iterators.baklava.{Multipart, FilePart}` for the upload body so the endpoints land in `target/baklava/openapi/openapi.yml` (and the ts-rest / simple-HTML outputs). One workaround in the spec: `baklava-http4s` 1.3.0 doesn't propagate the multipart boundary onto the outgoing request (http4s' `multipartEncoder` has `Headers.empty` per a long-standing TODO), so the spec declares `Content-Type: multipart/form-data; boundary=baklava-multipart-boundary` explicitly on each upload `onRequest`. Drop that declaration once baklava ships the fix.

## Things that catch people out

- **The disk backend writes meta first, data second.** The sidecar `.meta` file holds the rendered `Content-Type`. `get` only returns `Streamed` if both files exist. Failure cases:
  - Crash before meta lands → no files, `get` returns `NotFound`.
  - Crash between meta and data → orphan `.meta`, no data file, `get` returns `NotFound` (the `dataExists` check catches this); a subsequent `put` for the same key overwrites the orphan meta.
  - Crash mid-data-write → orphan `.meta` + truncated data file, `get` returns `Streamed` with corrupt bytes. Production deployments should run a sweeper or rely on filesystem-level integrity; this is the demo-grade tradeoff.
- **`metadata.sizeBytes` on `put` is a hint, not a contract.** Multipart parts often have no `Content-Length` header. The S3 backend uses it for `contentLength` when set (single-PUT optimization); when `0`, it falls back to chunked upload. Use the `IO[Long]` returned from `put` for accurate sizes.
- **`StorageKey` is just a string.** No leading slash, no scheme, no bucket prefix — just an object key relative to the configured bucket / disk root. Build it from your domain (e.g. `s"auctions/$auctionId/images/$id"`).
- **The redirect goes to S3, not back to the API.** Browsers download directly from S3 using the presigned URL. If your CORS / network setup blocks that, you'll see a working `303` followed by a failed download — the API never sees the second request.
