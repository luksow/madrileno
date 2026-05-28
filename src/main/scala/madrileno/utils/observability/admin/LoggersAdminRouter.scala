package madrileno.utils.observability.admin

import cats.effect.IO
import ch.qos.logback.classic.{Level, LoggerContext}
import madrileno.utils.http.BaseRouter
import madrileno.utils.observability.TelemetryContext
import org.slf4j.LoggerFactory
import pl.iterators.stir.marshalling.ToResponseMarshallable
import pl.iterators.stir.server.Route

import java.util.Locale
import scala.jdk.CollectionConverters.*

class LoggersAdminRouter(using TelemetryContext) extends BaseRouter {

  val routes: Route =
    (get & pathPrefix("loggers") & pathEndOrSingleSlash) {
      complete(IO.delay(listLoggers()).map[ToResponseMarshallable](Ok -> _))
    } ~
      (get & pathPrefix("loggers" / Segment) & pathEndOrSingleSlash) { name =>
        complete {
          IO.delay(findLogger(name)).map[ToResponseMarshallable] {
            case Some(dto) => Ok -> dto
            case None      => error(NotFound, "logger-not-found", s"No logger named '$name' is registered")
          }
        }
      } ~
      (post & pathPrefix("loggers" / Segment) & pathEndOrSingleSlash & entity(as[SetLoggerLevelRequest])) { (name, request) =>
        complete {
          IO.delay(setLoggerLevel(name, request.level)).map[ToResponseMarshallable] {
            case Right(Some(dto)) => Ok -> dto
            case Right(None)      => error(NotFound, "logger-not-found", s"No logger named '$name' is registered")
            case Left(msg)        => error(BadRequest, "invalid-log-level", msg)
          }
        }
      }

  private def loggerContext: LoggerContext =
    LoggerFactory.getILoggerFactory match {
      case ctx: LoggerContext => ctx
      case other              => throw new IllegalStateException(s"Expected Logback LoggerContext, got ${other.getClass.getName}")
    }

  private def listLoggers(): List[LoggerLevelDto] =
    loggerContext.getLoggerList.asScala.toList.map(LoggerLevelDto(_)).sortBy(_.name)

  private def findLogger(name: String): Option[LoggerLevelDto] =
    Option(loggerContext.exists(name)).map(LoggerLevelDto(_))

  private def setLoggerLevel(name: String, levelStr: Option[String]): Either[String, Option[LoggerLevelDto]] = {
    Option(loggerContext.exists(name)) match {
      case None => Right(None)
      case Some(logger) =>
        levelStr match {
          case None =>
            logger.setLevel(null) // scalafix:ok DisableSyntax.null
            Right(Some(LoggerLevelDto(logger)))
          case Some(s) =>
            parseLevel(s) match {
              case Some(level) =>
                logger.setLevel(level)
                Right(Some(LoggerLevelDto(logger)))
              case None =>
                Left(s"unknown log level: '$s' (valid: TRACE, DEBUG, INFO, WARN, ERROR, OFF)")
            }
        }
    }
  }

  private val ValidLevelNames: Set[String] = Set("TRACE", "DEBUG", "INFO", "WARN", "ERROR", "OFF")
  private def parseLevel(s: String): Option[Level] =
    Option(s).map(_.toUpperCase(Locale.ROOT)).filter(ValidLevelNames.contains).map(Level.toLevel)
}
