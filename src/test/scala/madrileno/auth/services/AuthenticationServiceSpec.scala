package madrileno.auth.services

import cats.effect.IO
import cats.effect.std.UUIDGen
import cats.effect.testing.scalatest.AsyncIOSpec
import madrileno.auth.domain.*
import madrileno.auth.repositories.*
import madrileno.support.{FakeAuthVerifier, TestData, TestGivens, TestMailpit, TestTransactor}
import madrileno.user.domain.*
import madrileno.user.repositories.UserRepository
import madrileno.utils.mailer.*
import madrileno.utils.observability.TelemetryContext
import madrileno.utils.task.*
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import org.typelevel.otel4s.metrics.Meter
import org.typelevel.otel4s.trace.Tracer

import java.net.URI
import java.time.Duration
import scala.concurrent.duration.*

class AuthenticationServiceSpec extends AsyncWordSpec with AsyncIOSpec with Matchers with TestTransactor with TestMailpit {

  private val testClock       = TestGivens.fixedClock()
  private val testUUIDGen     = TestGivens.deterministicUUIDs()
  given cats.effect.Clock[IO] = testClock
  given UUIDGen[IO]           = testUUIDGen
  given TelemetryContext      = TelemetryContext(Meter.noop[IO], Tracer.noop[IO], io.opentelemetry.api.OpenTelemetry.noop())

  private val jwtConfig  = JwtService.Config(secret = "test-secret-at-least-256-bits-long-for-hs256!!", validFor = Duration.ofMinutes(5))
  private val jwtService = JwtService(jwtConfig)

  private lazy val userRepo         = new UserRepository
  private lazy val userAuthRepo     = new UserAuthRepository
  private lazy val refreshTokenRepo = new RefreshTokenRepository

  private lazy val smtpSender = SmtpSender(MailerConfig(host = mailpitHost, port = mailpitSmtpPort, fromAddress = "test@example.com", tls = false))
  private lazy val scheduler  = Scheduler(transactor, SchedulerConfig(pollingInterval = 1.second))
  private lazy val client     = scheduler.client
  private lazy val mailer     = new Mailer(smtpSender, client, MailContext(baseUrl = URI("https://example.com")))

  private def freshVerifiedToken() = TestData.verifiedExternalToken()

  private def serviceWithFreshAuth() = {
    val token = freshVerifiedToken()
    val auth  = new FakeAuthVerifier(token)
    val svc   = new AuthenticationService(userAuthRepo, refreshTokenRepo, userRepo, auth, jwtService, transactor, mailer)
    (svc, token)
  }

  private val command = AuthenticateWithFirebaseCommand(FirebaseJwt("fake-token"), UserAgent("test-agent"), TestData.defaultIpAddress)

  "authenticateWithFirebase" should {
    "create a new user on first login" in {
      val (service, _) = serviceWithFreshAuth()
      service.authenticateWithFirebase(command).map {
        case AuthenticationResult.UserCreated(jwt, refreshToken) =>
          jwt.toString should not be empty
          refreshToken.id.toString should not be empty
        case other => fail(s"Expected UserCreated, got $other")
      }
    }

    "return Authenticated on subsequent login" in {
      val (service, _) = serviceWithFreshAuth()
      for {
        first  <- service.authenticateWithFirebase(command)
        second <- service.authenticateWithFirebase(command)
      } yield {
        first shouldBe a[AuthenticationResult.UserCreated]
        second shouldBe a[AuthenticationResult.Authenticated]
      }
    }

    "return InvalidToken for failed Firebase verification" in {
      val (service, _)        = serviceWithFreshAuth()
      val invalidTokenCommand = command.copy(firebaseJwt = FirebaseJwt("invalid-token"))
      service.authenticateWithFirebase(invalidTokenCommand).map { result =>
        result shouldBe AuthenticationResult.InvalidToken
      }
    }

    "issue a JWT that can be decoded" in {
      val (service, token) = serviceWithFreshAuth()
      service.authenticateWithFirebase(command).map {
        case AuthenticationResult.UserCreated(jwt, _) =>
          jwtService.decode[AuthContext](jwt.toString) match {
            case JwtService.DecodingResult.Decoded(ctx) =>
              ctx.fullName shouldBe token.profile.fullName
            case other => fail(s"Expected Decoded, got $other")
          }
        case other => fail(s"Expected UserCreated, got $other")
      }
    }

    "send welcome email on first login" in {
      val (service, _) = serviceWithFreshAuth()
      scheduler
        .run(oneTimeTasks = List(mailer.sendMailTask))
        .use { _ =>
          for {
            _ <- IO(clearMailpit())
            _ <- service.authenticateWithFirebase(command)
            _ <- IO.sleep(3.seconds)
          } yield ()
        }
        .map { _ =>
          val subject = getMailpitMessages.hcursor.downField("messages").downArray.get[String]("Subject").toOption.getOrElse("")
          subject should include("Welcome")
        }
    }
  }

  "authenticateWithRefreshToken" should {
    "authenticate with a valid refresh token" in {
      val (service, _) = serviceWithFreshAuth()
      for {
        created <- service.authenticateWithFirebase(command)
        refreshTokenId = created match {
                           case AuthenticationResult.UserCreated(_, rt) => rt.id
                           case other                                   => fail(s"Expected UserCreated, got $other")
                         }
        result <- service.authenticateWithRefreshToken(
                    AuthenticateWithRefreshTokenCommand(refreshTokenId, UserAgent("test-agent"), TestData.defaultIpAddress)
                  )
      } yield result shouldBe a[AuthenticationResult.Authenticated]
    }

    "reject an already-used refresh token" in {
      val (service, _) = serviceWithFreshAuth()
      for {
        created <- service.authenticateWithFirebase(command)
        refreshTokenId = created match {
                           case AuthenticationResult.UserCreated(_, rt)   => rt.id
                           case AuthenticationResult.Authenticated(_, rt) => rt.id
                           case other                                     => fail(s"Unexpected: $other")
                         }
        _ <- service.authenticateWithRefreshToken(
               AuthenticateWithRefreshTokenCommand(refreshTokenId, UserAgent("test-agent"), TestData.defaultIpAddress)
             )
        result <- service.authenticateWithRefreshToken(
                    AuthenticateWithRefreshTokenCommand(refreshTokenId, UserAgent("test-agent"), TestData.defaultIpAddress)
                  )
      } yield result shouldBe AuthenticationResult.InvalidToken
    }

    "reject an unknown refresh token" in {
      val (service, _) = serviceWithFreshAuth()
      service
        .authenticateWithRefreshToken(
          AuthenticateWithRefreshTokenCommand(TestData.randomRefreshTokenId(), UserAgent("test-agent"), TestData.defaultIpAddress)
        )
        .map(_ shouldBe AuthenticationResult.InvalidToken)
    }
  }
}
