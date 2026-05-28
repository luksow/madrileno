package madrileno.main

import cats.effect.IO
import cats.effect.testing.scalatest.AsyncIOSpec
import com.comcast.ip4s.*
import org.http4s.dsl.io.*
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.{HttpApp, Response}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import sttp.client4.basicRequest
import sttp.client4.httpurlconnection.HttpURLConnectionBackend
import sttp.model.Uri as SttpUri

import java.net.ServerSocket
import scala.concurrent.duration.*

class GracefulShutdownSpec extends AsyncWordSpec with AsyncIOSpec with Matchers {

  private def freePort(): Int = {
    val s = new ServerSocket(0)
    try s.getLocalPort
    finally s.close()
  }

  private val testHost: Host = host"127.0.0.1"

  "EmberServerBuilder shutdown" should {
    "let an in-flight request finish during the shutdown drain window" in {
      val port = Port.fromInt(freePort()).get
      val slowApp = HttpApp[IO] { _ =>
        IO.sleep(300.millis) *> IO.pure(Response[IO](Ok).withEntity("done"))
      }
      val server = EmberServerBuilder
        .default[IO]
        .withHost(testHost)
        .withPort(port)
        .withShutdownTimeout(5.seconds)
        .withHttpApp(slowApp)
        .build
      val uri = SttpUri.parse(s"http://127.0.0.1:${port.value}/").toOption.get

      val backend = HttpURLConnectionBackend()
      (for {
        allocated <- server.allocated
        (_, release) = allocated
        inflight     <- IO.blocking(basicRequest.get(uri).send(backend)).start
        _            <- IO.sleep(50.millis) // give the request time to land in the handler
        releaseFiber <- release.start
        response     <- inflight.joinWithNever
        _            <- releaseFiber.joinWithNever
      } yield response).asserting { response =>
        response.code.code shouldBe 200
        response.body shouldBe Right("done")
      }
    }

    "refuse new connections once shutdown has completed" in {
      val port = Port.fromInt(freePort()).get
      val app  = HttpApp[IO](_ => IO.pure(Response[IO](Ok).withEntity("ok")))
      val server = EmberServerBuilder
        .default[IO]
        .withHost(testHost)
        .withPort(port)
        .withShutdownTimeout(5.seconds)
        .withHttpApp(app)
        .build
      val uri = SttpUri.parse(s"http://127.0.0.1:${port.value}/").toOption.get

      val backend = HttpURLConnectionBackend()
      (for {
        allocated <- server.allocated
        (_, release) = allocated
        ok <- IO.blocking(basicRequest.get(uri).send(backend))
        _ = ok.body shouldBe Right("ok")
        _            <- release
        afterRelease <- IO.blocking(basicRequest.get(uri).send(backend)).attempt
      } yield afterRelease).asserting(_.isLeft shouldBe true)
    }
  }
}
