package madrileno.utils.logging

import org.http4s.Header
import org.typelevel.ci.CIString
import pl.iterators.stir.server.Directive1
import pl.iterators.stir.server.Directives.*

trait CorrelationIdDirectives {
  def withCorrelationId(headerName: String, correlationIdGenerator: => String): Directive1[String] =
    optionalHeaderValueByName(headerName).flatMap { correlationIdOpt =>
      val correlationId = correlationIdOpt.getOrElse(correlationIdGenerator)
      respondWithHeader(Header.Raw(CIString(headerName), correlationId)).tflatMap { _ =>
        provide(correlationId)
      }
    }
}

object CorrelationIdDirectives extends CorrelationIdDirectives
