package madrileno.auth.routers

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import madrileno.auth.domain.FirebaseJwt
import madrileno.auth.routers.dto.{AuthWithFirebaseRequest, AuthWithRefreshTokenRequest, AuthenticatedResponse}
import madrileno.support.TestApplicationLoader
import madrileno.utils.json.JsonProtocol.*
import org.http4s.headers.Authorization
import org.http4s.server.websocket.WebSocketBuilder2
import org.http4s.{AuthScheme, Credentials, EntityEncoder, HttpRoutes, Method, Request, Response, Status, Uri}
import org.scalatest.funspec.AnyFunSpec
import org.scalatest.matchers.should.Matchers
import org.slf4j.{Logger, LoggerFactory}
import pl.iterators.stir.server.ToHttpRoutes

import scala.jdk.CollectionConverters.*

/** The leak test: whatever the logging implementation does, credentials issued or presented on the auth routes must not appear in any log line. Runs
  * the real route tree at debug level with body logging on, which is the dev configuration.
  */
class SecretsStayOutOfLogsSpec extends AnyFunSpec with Matchers with TestApplicationLoader {

  private lazy val routes: HttpRoutes[IO] = application.routes(WebSocketBuilder2[IO].unsafeRunSync()).toHttpRoutes

  private val externalToken = "test-token"
  private val bogusBearer   = "bogus.bearer.token"

  describe("auth routes") {
    it("keep every credential out of the logs while leaving fingerprints to correlate by") {
      val ((login, refreshed), logged) = capturingLogs {
        val login     = decode[AuthenticatedResponse](post("/v1/auth/firebase", AuthWithFirebaseRequest(FirebaseJwt(externalToken))))
        val refreshed = decode[AuthenticatedResponse](post("/v1/auth/refresh-token", AuthWithRefreshTokenRequest(login.refreshToken)))
        val replayed  = drain(post("/v1/auth/refresh-token", AuthWithRefreshTokenRequest(login.refreshToken)))
        val rejected  = drain(get("/v1/auth/sessions", bearer = bogusBearer))
        replayed.status shouldBe Status.Unauthorized
        rejected.status shouldBe Status.Unauthorized
        (login, refreshed)
      }

      val secrets = List(
        externalToken,
        bogusBearer,
        login.jwt.unwrap,
        login.refreshToken.unwrap.toString,
        refreshed.jwt.unwrap,
        refreshed.refreshToken.unwrap.toString
      )
      logged should not be empty
      secrets.foreach { secret =>
        withClue(s"log lines containing a credential:\n${logged.filter(_.contains(secret)).mkString("\n")}\n") {
          logged.exists(_.contains(secret)) shouldBe false
        }
      }

      // Correlation survives redaction: the replayed token is traceable from the request body to the service's warning.
      val replayedFingerprint = login.refreshToken.fingerprint.value
      logged.count(_.contains(replayedFingerprint)) should be >= 2
      logged.exists(_.contains("POST /v1/auth/refresh-token")) shouldBe true
    }
  }

  private def post[A](path: String, body: A)(using EntityEncoder[IO, A]): Response[IO] =
    routes.orNotFound.run(Request[IO](Method.POST, Uri.unsafeFromString(path)).withEntity(body)).unsafeRunSync()

  private def get(path: String, bearer: String): Response[IO] =
    routes.orNotFound
      .run(Request[IO](Method.GET, Uri.unsafeFromString(path)).putHeaders(Authorization(Credentials.Token(AuthScheme.Bearer, bearer))))
      .unsafeRunSync()

  // Response log lines are written while the body streams out, so every response is drained before assertions.
  private def decode[A: Decoder](response: Response[IO]): A = {
    response.status shouldBe Status.Ok
    response.as[A].unsafeRunSync()
  }

  private def drain(response: Response[IO]): Response[IO] = {
    response.bodyText.compile.drain.unsafeRunSync()
    response
  }

  private def capturingLogs[A](body: => A): (A, List[String]) =
    LoggerFactory.getILoggerFactory match {
      case context: LoggerContext =>
        val root     = context.getLogger(Logger.ROOT_LOGGER_NAME)
        val appender = new ListAppender[ILoggingEvent]
        appender.start()
        root.addAppender(appender)
        try {
          val result = body
          (result, appender.list.asScala.toList.map(render))
        } finally {
          root.detachAppender(appender)
          appender.stop()
        }
      case other => fail(s"expected logback, found ${other.getClass.getName}")
    }

  private def render(event: ILoggingEvent): String = {
    val cause = Option(event.getThrowableProxy).map(proxy => s" ${proxy.getClassName}: ${proxy.getMessage}").getOrElse("")
    s"${event.getFormattedMessage}$cause"
  }
}
