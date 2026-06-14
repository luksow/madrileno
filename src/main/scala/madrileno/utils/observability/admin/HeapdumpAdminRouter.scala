package madrileno.utils.observability.admin

import cats.effect.IO
import cats.effect.kernel.Clock
import com.sun.management.HotSpotDiagnosticMXBean
import madrileno.utils.http.{BaseRouter, RateLimitDirectives, RateLimiter, RateLimiterRuntime}
import madrileno.utils.json.JsonProtocol.*
import madrileno.utils.observability.TelemetryContext
import pl.iterators.stir.marshalling.ToResponseMarshallable
import pl.iterators.stir.server.Route

import java.io.{File, IOException}
import java.lang.management.ManagementFactory
import java.nio.file.FileAlreadyExistsException
import scala.concurrent.duration.*

final case class HeapdumpResultDto(
  path: String,
  sizeBytes: Long,
  liveOnly: Boolean,
  tookMillis: Long)
    derives Encoder.AsObject,
      Decoder

class HeapdumpAdminRouter(rateLimiterRuntime: RateLimiterRuntime)(using TelemetryContext) extends BaseRouter with RateLimitDirectives {
  override protected val rateLimiter: RateLimiter = rateLimiterRuntime.rateLimiter

  val routes: Route =
    (post & pathPrefix("heapdump") & pathEndOrSingleSlash & rateLimited(
      "admin.heapdump",
      to = 3,
      within = 5.minutes,
      by = _ => "global"
    ) & parameters("live".as[Boolean] ? true, "path".?)) { (live, pathOverride) =>
      complete {
        dump(live, pathOverride).map[ToResponseMarshallable] {
          case Right(dto)             => Ok -> dto
          case Left(FileExists)       => error(Conflict, "heapdump-file-exists", "Heap dump target file already exists; pick a different path")
          case Left(NotHotSpot)       => error(NotImplemented, "heapdump-not-supported", "HotSpotDiagnosticMXBean is not available on this JVM")
          case Left(Rejected(msg))    => error(BadRequest, "heapdump-bad-request", msg)
          case Left(WriteFailed(msg)) => error(InternalServerError, "heapdump-write-failed", s"Heap dump failed: $msg")
        }
      }
    }

  private def dump(liveOnly: Boolean, pathOverride: Option[String]): IO[Either[DumpFailure, HeapdumpResultDto]] =
    for {
      targetPath <- pathOverride.fold(HeapdumpAdminRouter.defaultPath)(IO.pure)
      file = new File(targetPath)
      exists <- IO.blocking(file.exists())
      result <-
        if (exists) IO.pure(Left(FileExists))
        else
          hotSpotBean.fold(IO.pure(Left(NotHotSpot): Either[DumpFailure, HeapdumpResultDto])) { bean =>
            Clock[IO]
              .timed(IO.blocking(bean.dumpHeap(targetPath, liveOnly)))
              .flatMap { case (took, _) =>
                IO.blocking(file.length()).map { size =>
                  Right(HeapdumpResultDto(targetPath, size, liveOnly, took.toMillis)): Either[DumpFailure, HeapdumpResultDto]
                }
              }
              .recover {
                case _: FileAlreadyExistsException => Left(FileExists)
                case e: IllegalArgumentException   => Left(Rejected(Option(e.getMessage).getOrElse("invalid heapdump arguments")))
                case e: IOException                => Left(WriteFailed(Option(e.getMessage).getOrElse(e.getClass.getName)))
              }
          }
    } yield result

  private lazy val hotSpotBean: Option[HotSpotDiagnosticMXBean] =
    Option(ManagementFactory.getPlatformMXBean(classOf[HotSpotDiagnosticMXBean]))
}

object HeapdumpAdminRouter {
  private[admin] def defaultPath: IO[String] =
    IO.realTimeInstant.map { now =>
      val tmp       = System.getProperty("java.io.tmpdir")
      val timestamp = now.toString.replace(':', '-').replace('.', '-')
      val pid       = ProcessHandle.current().pid()
      s"$tmp/madrileno-heap-$timestamp-$pid.hprof"
    }
}

private sealed trait DumpFailure
private case object FileExists                        extends DumpFailure
private case object NotHotSpot                        extends DumpFailure
private final case class Rejected(message: String)    extends DumpFailure
private final case class WriteFailed(message: String) extends DumpFailure
