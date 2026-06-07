package madrileno.auction.gateways

import cats.effect.IO
import io.chrisdavenport.circuit.CircuitBreaker
import io.circe.Encoder
import io.circe.derivation.{Configuration, ConfiguredCodec}
import io.circe.syntax.*
import madrileno.auction.domain.*
import madrileno.utils.cache.CacheRuntime
import madrileno.utils.observability.{LoggingSupport, TelemetryContext}
import retry.RetryPolicies.*
import retry.{HandlerDecision, RetryDetails, RetryPolicy, retryingOnErrors}
import sttp.capabilities.fs2.Fs2Streams
import sttp.client4.circe.*
import sttp.client4.{SttpClientException, UriContext, WebSocketStreamBackend, basicRequest}
import sttp.model.MediaType

import java.io.IOException
import java.text.Normalizer
import java.util.concurrent.TimeoutException
import scala.annotation.tailrec
import scala.concurrent.duration.*

trait VivinoGateway {
  def findRating(wineName: WineName, vintage: Option[Vintage]): IO[Option[VivinoRating]]
}

object VivinoGateway {
  private[gateways] val similarityThreshold = 0.85

  private[gateways] val retryPolicy: RetryPolicy[IO, Any] =
    limitRetries[IO](2).join(exponentialBackoff[IO](200.millis))

  private[gateways] def isTransient(t: Throwable): Boolean = t match {
    case _: TimeoutException    => true
    case _: SttpClientException => true
    case _: IOException         => true
    case _                      => false
  }

  private[gateways] def pickBestMatch(
    wineName: WineName,
    vintage: Option[Vintage],
    hits: List[AlgoliaHit]
  ): Option[VivinoRating] = {
    val normalizedTarget = Similarity.normalize(wineName.unwrap)

    val candidates = for {
      hit     <- hits
      hitName <- hit.name.toList
      stats <- vintage match {
                 case Some(v) =>
                   for {
                     vintages <- hit.vintages.toList
                     vn       <- vintages
                     rawYear  <- vn.year.toList
                     year     <- rawYear.toIntOption.toList if v.unwrap == year
                     s        <- vn.statistics.toList
                   } yield s
                 case None =>
                   hit.statistics.toList
               }
      status  <- stats.status.toList if status == "Normal"
      avg     <- stats.ratingsAverage.toList
      count   <- stats.ratingsCount.toList if count > 0
      rating  <- Rating.validate(avg).toOption.toList
      ratings <- RatingsCount.validate(count).toOption.toList
      // Vivino strips the producer from `name` (e.g. "Grange" for Penfolds Grange). Score
      // both name-only and winery+name; keep whichever is higher, so wines that already
      // include the producer in the name (Romanée-Conti, Sassicaia) aren't penalised by
      // concatenation.
      nameSim = Similarity.jaroWinkler(normalizedTarget, Similarity.normalize(hitName))
      wineryNameSim = hit.winery
                        .flatMap(_.name)
                        .map(w => Similarity.jaroWinkler(normalizedTarget, Similarity.normalize(s"$w $hitName")))
                        .getOrElse(0.0)
      similarity = math.max(nameSim, wineryNameSim)
      if similarity >= similarityThreshold
    } yield (similarity, VivinoRating(rating, ratings))

    candidates.sortBy(-_._1).headOption.map(_._2)
  }

  // Outbound request keeps camelCase; Algolia rejects hits_per_page with a 400.
  private[gateways] final case class AlgoliaQuery(query: String, hitsPerPage: Int) derives Encoder.AsObject

  // Inbound response uses snake_case (ratings_count, ratings_average); scoped to decoders below.
  private[gateways] given Configuration = Configuration.default.withSnakeCaseMemberNames

  private[gateways] final case class AlgoliaResponse(hits: List[AlgoliaHit]) derives ConfiguredCodec
  private[gateways] final case class AlgoliaHit(
    name: Option[String],
    winery: Option[AlgoliaWinery],
    statistics: Option[AlgoliaStatistics],
    vintages: Option[List[AlgoliaVintage]])
      derives ConfiguredCodec
  private[gateways] final case class AlgoliaWinery(name: Option[String]) derives ConfiguredCodec
  private[gateways] final case class AlgoliaVintage(year: Option[String], statistics: Option[AlgoliaStatistics]) derives ConfiguredCodec
  private[gateways] final case class AlgoliaStatistics(
    ratingsCount: Option[Int],
    ratingsAverage: Option[BigDecimal],
    status: Option[String])
      derives ConfiguredCodec
}

class VivinoGatewayLive(
  http: WebSocketStreamBackend[IO, Fs2Streams[IO]],
  cacheRuntime: CacheRuntime,
  circuitBreaker: IO[CircuitBreaker[IO]]
)(using TelemetryContext)
    extends VivinoGateway
    with LoggingSupport {
  import VivinoGateway.*

  private val endpoint       = uri"https://9takgwjuxl-dsn.algolia.net/1/indexes/WINES_prod/query"
  private val applicationId  = "9TAKGWJUXL"
  private val apiKey         = "60c11b2f1068885161d95ca068d3a6ae"
  private val requestTimeout = 3.seconds

  private val cache = cacheRuntime.expiring[(WineName, Option[Vintage]), Option[VivinoRating]](expireAfterWrite = 24.hours, maxSize = 10_000)

  override def findRating(wineName: WineName, vintage: Option[Vintage]): IO[Option[VivinoRating]] =
    cache.get((wineName, vintage)).flatMap {
      case Some(cached) => IO.pure(cached)
      case None =>
        val singleAttempt = fetch(wineName, vintage).timeout(requestTimeout)
        circuitBreaker
          .flatMap(cb =>
            cb.protect(
              retryingOnErrors(singleAttempt)(
                policy = retryPolicy,
                errorHandler = (e: Throwable, details: RetryDetails) =>
                  if (isTransient(e))
                    logger
                      .debug(
                        s"Vivino lookup transient failure (retry ${details.retriesSoFar + 1}) for $wineName ${vintage.map(_.unwrap)}: ${e.getClass.getSimpleName}"
                      )
                      .as(HandlerDecision.Continue)
                  else
                    IO.pure(HandlerDecision.Stop)
              )
            )
          )
          .flatTap(result => cache.put((wineName, vintage), result))
          .handleErrorWith {
            case _: CircuitBreaker.RejectedExecution =>
              logger.debug(s"Vivino circuit breaker open, fast-failing $wineName ${vintage.map(_.unwrap)}").as(None)
            case t =>
              logger.warn(t)(s"Vivino lookup failed for $wineName ${vintage.map(_.unwrap)}").as(None)
          }
    }

  private def fetch(wineName: WineName, vintage: Option[Vintage]): IO[Option[VivinoRating]] = {
    val queryText = vintage.map(v => s"${wineName.unwrap} ${v.unwrap}").getOrElse(wineName.unwrap)
    val payload   = AlgoliaQuery(query = queryText, hitsPerPage = 6).asJson.noSpaces
    val request = basicRequest
      .post(endpoint)
      .header("x-algolia-api-key", apiKey)
      .header("x-algolia-application-id", applicationId)
      .contentType(MediaType.ApplicationJson)
      .body(payload)
      .response(asJson[AlgoliaResponse])

    request.send(http).flatMap { response =>
      response.body match {
        case Right(parsed) => IO.pure(pickBestMatch(wineName, vintage, parsed.hits))
        case Left(error)   => IO.raiseError(error)
      }
    }
  }
}

private[gateways] object Similarity {
  def normalize(s: String): String = {
    val decomposed = Normalizer.normalize(s, Normalizer.Form.NFD)
    val stripped   = decomposed.replaceAll("\\p{M}", "")
    stripped.toLowerCase.replaceAll("[^a-z0-9 ]", " ").replaceAll("\\s+", " ").trim
  }

  def jaroWinkler(a: String, b: String): Double = {
    val j = jaro(a, b)
    if (j < 0.7) j else j + commonPrefixLength(a, b, maxLen = 4) * 0.1 * (1 - j)
  }

  private def jaro(s1: String, s2: String): Double = {
    if (s1.isEmpty && s2.isEmpty) 1.0
    else if (s1.isEmpty || s2.isEmpty) 0.0
    else {
      val window = math.max(0, math.max(s1.length, s2.length) / 2 - 1)

      @tailrec
      def matchLoop(
        i: Int,
        acc: List[(Int, Int)],
        used: Set[Int]
      ): List[(Int, Int)] =
        if (i >= s1.length) acc.reverse
        else {
          val start = math.max(0, i - window)
          val end   = math.min(s2.length - 1, i + window)
          (start to end).find(j => !used.contains(j) && s1(i) == s2(j)) match {
            case Some(j) => matchLoop(i + 1, (i, j) :: acc, used + j)
            case None    => matchLoop(i + 1, acc, used)
          }
        }

      val matches = matchLoop(0, Nil, Set.empty)
      if (matches.isEmpty) 0.0
      else {
        val s1Chars        = matches.map { case (i, _) => s1(i) }
        val s2Chars        = matches.sortBy(_._2).map { case (_, j) => s2(j) }
        val transpositions = s1Chars.zip(s2Chars).count { case (x, y) => x != y } / 2.0
        val m              = matches.size.toDouble
        (m / s1.length + m / s2.length + (m - transpositions) / m) / 3.0
      }
    }
  }

  private def commonPrefixLength(
    a: String,
    b: String,
    maxLen: Int
  ): Int = {
    val limit = math.min(maxLen, math.min(a.length, b.length))
    (0 until limit).takeWhile(i => a(i) == b(i)).length
  }
}
