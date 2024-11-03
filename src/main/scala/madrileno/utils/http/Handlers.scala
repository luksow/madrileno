package madrileno.utils.http

import org.http4s.Status.*
import io.circe.DecodingFailure
import madrileno.utils.observability.{LoggingSupport, TelemetryContext}
import org.http4s.{Headers, Status}
import org.http4s.headers.{Allow, `WWW-Authenticate`}
import org.http4s.otel4s.middleware.instances.all.*
import pl.iterators.stir.server.AuthenticationFailedRejection.{CredentialsMissing, CredentialsRejected}
import pl.iterators.stir.server.*

import java.net.URI
import scala.reflect.ClassTag

trait Handlers extends LoggingSupport with BaseRouter {
  private def handlerComplete[E: Encoder](
    status: Status,
    typeTag: String,
    title: String,
    detail: Option[String],
    additionalHeaders: Headers,
    extension: E,
    loggingDirective: Directive0
  )(using tc: TelemetryContext
  ) = {
    loggingDirective {
      onSuccess(tc.tracer.propagate(Headers.empty)) { tracingHeaders =>
        onSuccess(tc.tracer.currentSpanContext) { spanContext =>
          mapResponseHeaders(_ ++ additionalHeaders ++ tracingHeaders) {
            val error =
              Error(Some(new URI(typeTag)), Some(status), Some(title), detail, spanContext.map(c => new URI(s"trace-id:${c.traceIdHex}")), extension)
            complete(status -> error)
          }
        }
      }
    }
  }

  private def typeFromRejection[T <: Rejection](implicit cls: ClassTag[T]): String =
    "rejection:" + cls.runtimeClass.getSimpleName
      .replace("Rejection", "")
      .filterNot(_ == '$')
      .replaceAll("([a-z])([A-Z])", "$1-$2")
      .toLowerCase // kebabify

  def exceptionHandler(loggingDirective: Directive0)(using tc: TelemetryContext): ExceptionHandler =
    ExceptionHandler {
      case e: IllegalArgumentException =>
        handlerComplete(
          BadRequest,
          "rejection:static-validation-failed",
          "Validation failed",
          Some(e.getMessage),
          Headers.empty,
          (),
          loggingDirective
        )
      case e: Exception =>
        loggingDirective & complete {
          for {
            spanContext <- tc.tracer.currentSpanContext
            headers     <- tc.tracer.propagate(Headers.empty)
            _           <- logger.error(e)(s"Internal error, trace-id: ${spanContext.map(_.traceIdHex).getOrElse("000")}\n${e.getMessage}")
          } yield {
            (
              InternalServerError,
              headers,
              Error(
                Some(new URI("error:internal-error")),
                Some(InternalServerError),
                Some("Internal error"),
                None,
                spanContext.map(c => new URI(s"trace-id:${c.traceIdHex}")),
                ()
              )
            )
          }
        }
    }

  def rejectionHandler(loggingDirective: Directive0)(using TelemetryContext): RejectionHandler =
    RejectionHandler
      .newBuilder()
      // custom rejections
      .handleAll[SchemeRejection] { rejections =>
        val schemes = rejections.map(_.supported).mkString(", ")
        handlerComplete(
          BadRequest,
          typeFromRejection[SchemeRejection],
          "Uri scheme not allowed",
          Some(s"Supported schemes: ${schemes.mkString(", ")}"),
          Headers.empty,
          Map("supportedSchemes" -> schemes),
          loggingDirective
        )
      }
      .handle { case ValidationRejection(msg, _) =>
        handlerComplete(BadRequest, "rejection:static-validation-failed", "Validation failed", Some(msg), Headers.empty, (), loggingDirective)
      }
      .handle { case MalformedRequestContentRejection(msg, throwable) =>
        throwable match {
          case df: DecodingFailure =>
            handlerComplete(
              BadRequest,
              "rejection:static-validation-failed",
              "Validation failed",
              df.pathToRootString.fold(Option(df.message))((p: String) => Some(s"$p: ${df.message}")),
              Headers.empty,
              df.pathToRootString.fold(Map("message" -> df.message))(p => Map("path" -> p, "message" -> df.message)),
              loggingDirective
            )
          case _ =>
            handlerComplete(
              BadRequest,
              typeFromRejection[MalformedRequestContentRejection],
              "The request content was malformed",
              Some(msg),
              Headers.empty,
              (),
              loggingDirective
            )
        }
      }
      .handle { case MalformedFormFieldRejection(name, msg, _) =>
        handlerComplete(
          BadRequest,
          typeFromRejection[MalformedFormFieldRejection],
          "The form field was malformed",
          Some(s"$name: $msg"),
          Headers.empty,
          Map("fieldName" -> name, "message" -> msg),
          loggingDirective
        )
      }
      .handle { case MalformedHeaderRejection(headerName, msg, _) =>
        handlerComplete(
          BadRequest,
          typeFromRejection[MalformedHeaderRejection],
          "The value of HTTP header was malformed",
          Some(s"$headerName: $msg"),
          Headers.empty,
          Map("headerName" -> headerName, "message" -> msg),
          loggingDirective
        )
      }
      .handle { case MalformedQueryParamRejection(name, msg, _) =>
        handlerComplete(
          BadRequest,
          typeFromRejection[MalformedQueryParamRejection],
          "The query parameter was malformed",
          Some(s"$name: $msg"),
          Headers.empty,
          Map("queryParamName" -> name, "message" -> msg),
          loggingDirective
        )
      }
      .handle { case InvalidRequiredValueForQueryParamRejection(paramName, requiredValue, _) =>
        handlerComplete(
          BadRequest,
          typeFromRejection[InvalidRequiredValueForQueryParamRejection],
          "The request is missing a required query parameter",
          Some(s"$paramName: $requiredValue"),
          Headers.empty,
          Map("queryParamName" -> paramName, "requiredValue" -> requiredValue),
          loggingDirective
        )
      }
      .handle { case EntityRejection(e) =>
        handlerComplete(
          BadRequest,
          typeFromRejection[EntityRejection],
          "The request entity was invalid: " + e.getMessage,
          None,
          Headers.empty,
          (),
          loggingDirective
        )
      }
      .handle { case MissingCookieRejection(cookieName) =>
        handlerComplete(
          BadRequest,
          typeFromRejection[MissingCookieRejection],
          "The request is missing a required cookie",
          Some(cookieName),
          Headers.empty,
          Map("cookieName" -> cookieName),
          loggingDirective
        )
      }
      .handle { case MissingFormFieldRejection(fieldName) =>
        handlerComplete(
          BadRequest,
          typeFromRejection[MissingFormFieldRejection],
          "The request is missing a required form field",
          Some(fieldName),
          Headers.empty,
          Map("fieldName" -> fieldName),
          loggingDirective
        )
      }
      .handle { case MissingHeaderRejection(headerName) =>
        handlerComplete(
          BadRequest,
          typeFromRejection[MissingHeaderRejection],
          "The request is missing a required HTTP header",
          Some(headerName),
          Headers.empty,
          Map("headerName" -> headerName),
          loggingDirective
        )
      }
      .handle { case MissingQueryParamRejection(paramName) =>
        handlerComplete(
          BadRequest,
          typeFromRejection[MissingQueryParamRejection],
          "The request is missing a required query parameter",
          Some(paramName),
          Headers.empty,
          Map("queryParamName" -> paramName),
          loggingDirective
        )
      }
      .handle { case AuthorizationFailedRejection =>
        handlerComplete(
          Unauthorized,
          typeFromRejection[AuthorizationFailedRejection.type],
          "The supplied authentication is not authorized to access this resource",
          None,
          Headers.empty,
          (),
          loggingDirective
        )
      }
      .handleAll[AuthenticationFailedRejection] { rejections =>
        val rejectionMessage = rejections.head.cause match {
          case CredentialsMissing  => "The resource requires authentication, which was not supplied with the request"
          case CredentialsRejected => "The supplied authentication is invalid"
        }
        val authenticateHeaders = rejections.map(r => `WWW-Authenticate`(r.challenge))
        handlerComplete(
          Unauthorized,
          typeFromRejection[AuthenticationFailedRejection],
          "Could not authorize",
          Some(rejectionMessage),
          Headers(authenticateHeaders),
          (),
          loggingDirective
        )
      }
      .handleAll[MethodRejection] { rejections =>
        val (methods, names) = rejections.map(r => r.supported -> r.supported.name).unzip
        handlerComplete(
          MethodNotAllowed,
          typeFromRejection[MethodRejection],
          "HTTP method not allowed",
          Some("Supported methods: " + names.mkString(", ")),
          Headers(Allow(methods*)),
          Map("supportedMethods" -> names),
          loggingDirective
        )
      }
      .handleNotFound {
        handlerComplete(NotFound, "rejection:route-not-found", "Route not found", None, Headers.empty, (), loggingDirective)
      }
      .result()
      .seal
}
