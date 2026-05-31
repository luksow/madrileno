package madrileno.utils.observability.admin

import cats.effect.IO
import com.sun.management.HotSpotDiagnosticMXBean
import madrileno.utils.http.BaseRouter
import madrileno.utils.json.JsonProtocol.*
import madrileno.utils.observability.TelemetryContext
import pl.iterators.stir.marshalling.ToResponseMarshallable
import pl.iterators.stir.server.Route

import java.io.{File, IOException}
import java.lang.management.ManagementFactory
import java.time.Instant

final case class HeapdumpResultDto(
  path: String,
  sizeBytes: Long,
  liveOnly: Boolean,
  tookMillis: Long)
    derives Encoder.AsObject,
      Decoder

class HeapdumpAdminRouter(using TelemetryContext) extends BaseRouter {

  val routes: Route =
    (post & pathPrefix("heapdump") & pathEndOrSingleSlash & parameters("live".as[Boolean] ? true, "path".?)) { (live, pathOverride) =>
      complete {
        IO.blocking(dump(live, pathOverride)).map[ToResponseMarshallable] {
          case Right(dto)             => Ok -> dto
          case Left(FileExists)       => error(Conflict, "heapdump-file-exists", "Heap dump target file already exists; pick a different path")
          case Left(NotHotSpot)       => error(NotImplemented, "heapdump-not-supported", "HotSpotDiagnosticMXBean is not available on this JVM")
          case Left(WriteFailed(msg)) => error(InternalServerError, "heapdump-write-failed", s"Heap dump failed: $msg")
        }
      }
    }

  private def dump(liveOnly: Boolean, pathOverride: Option[String]): Either[DumpFailure, HeapdumpResultDto] = {
    val targetPath = pathOverride.getOrElse(HeapdumpAdminRouter.defaultPath())
    val file       = new File(targetPath)
    if (file.exists()) Left(FileExists)
    else
      hotSpotBean match {
        case None => Left(NotHotSpot)
        case Some(bean) =>
          val started = System.currentTimeMillis()
          try {
            bean.dumpHeap(targetPath, liveOnly)
            val took = System.currentTimeMillis() - started
            Right(HeapdumpResultDto(path = targetPath, sizeBytes = file.length(), liveOnly = liveOnly, tookMillis = took))
          } catch {
            case e: IOException => Left(WriteFailed(Option(e.getMessage).getOrElse(e.getClass.getName)))
          }
      }
  }

  private def hotSpotBean: Option[HotSpotDiagnosticMXBean] =
    try Some(ManagementFactory.getPlatformMXBean(classOf[HotSpotDiagnosticMXBean]))
    catch { case _: IllegalArgumentException => None }
}

object HeapdumpAdminRouter {
  private[admin] def defaultPath(): String = {
    val tmp       = System.getProperty("java.io.tmpdir")
    val timestamp = Instant.now().toString.replace(':', '-').replace('.', '-')
    val pid       = ProcessHandle.current().pid()
    s"$tmp/madrileno-heap-$timestamp-$pid.hprof"
  }
}

private sealed trait DumpFailure
private case object FileExists                        extends DumpFailure
private case object NotHotSpot                        extends DumpFailure
private final case class WriteFailed(message: String) extends DumpFailure
