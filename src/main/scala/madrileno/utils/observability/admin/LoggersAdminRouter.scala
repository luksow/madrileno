package madrileno.utils.observability.admin

import cats.effect.IO
import ch.qos.logback.classic.{Level, Logger, LoggerContext}
import madrileno.utils.http.BaseRouter
import org.slf4j.{Logger as Slf4jLogger, LoggerFactory}
import pl.iterators.stir.marshalling.ToResponseMarshallable
import pl.iterators.stir.server.Route

import scala.jdk.CollectionConverters.*

class LoggersAdminRouter extends BaseRouter {

  val routes: Route =
    (get & path("loggers") & pathEndOrSingleSlash) {
      complete(IO.delay(listLoggers()).map[ToResponseMarshallable](Ok -> _))
    } ~
      (get & path("loggers" / Segment) & pathEndOrSingleSlash) { name =>
        complete {
          IO.delay(getLogger(name)).map[ToResponseMarshallable] {
            case Some(dto) => Ok       -> dto
            case None      => NotFound -> ""
          }
        }
      } ~
      (post & path("loggers" / Segment) & pathEndOrSingleSlash & entity(as[SetLoggerLevelRequest])) { (name, request) =>
        complete {
          IO.delay(setLoggerLevel(name, request.level)).map[ToResponseMarshallable] {
            case Right(dto)  => Ok         -> dto
            case Left(error) => BadRequest -> error
          }
        }
      }

  private def loggerContext: LoggerContext =
    LoggerFactory.getILoggerFactory match {
      case ctx: LoggerContext => ctx
      case other              => throw new IllegalStateException(s"Expected Logback LoggerContext, got ${other.getClass.getName}")
    }

  private def listLoggers(): List[LoggerLevelDto] =
    loggerContext.getLoggerList.asScala.toList.map(toDto).sortBy(_.name)

  private def getLogger(name: String): Option[LoggerLevelDto] = {
    val raw = loggerContext.exists(name)
    Option(raw).map(toDto)
  }

  private def setLoggerLevel(name: String, levelStr: Option[String]): Either[String, LoggerLevelDto] = {
    val logger: Logger = loggerContext.getLogger(name)
    levelStr match {
      case None =>
        logger.setLevel(null) // scalafix:ok DisableSyntax.null
        Right(toDto(logger))
      case Some(s) =>
        parseLevel(s) match {
          case Some(level) =>
            logger.setLevel(level)
            Right(toDto(logger))
          case None =>
            Left(s"unknown log level: '$s' (valid: TRACE, DEBUG, INFO, WARN, ERROR, OFF)")
        }
    }
  }

  private val ValidLevelNames: Set[String] = Set("TRACE", "DEBUG", "INFO", "WARN", "ERROR", "OFF")
  private def parseLevel(s: String): Option[Level] =
    Option(s).map(_.toUpperCase).filter(ValidLevelNames.contains).map(Level.toLevel)

  private def toDto(logger: Logger): LoggerLevelDto = {
    val configured = Option(logger.getLevel).map(_.toString)
    val effective  = logger.getEffectiveLevel.toString
    val name       = if (logger.getName == Slf4jLogger.ROOT_LOGGER_NAME) "ROOT" else logger.getName
    LoggerLevelDto(name = name, configuredLevel = configured, effectiveLevel = effective)
  }
}
