package madrileno.auction.gateways

import madrileno.auction.domain.*
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class VivinoGatewaySpec extends AnyWordSpec with Matchers {

  "Similarity.normalize" should {
    "lowercase, strip diacritics, collapse whitespace, drop punctuation" in {
      Similarity.normalize("Château Margaux, 1er Cru") shouldBe "chateau margaux 1er cru"
      Similarity.normalize("  Domaine de la  Romanée-Conti  ") shouldBe "domaine de la romanee conti"
      Similarity.normalize("Wine #42!") shouldBe "wine 42"
    }
  }

  "Similarity.jaroWinkler" should {
    "return 1.0 for identical strings" in {
      Similarity.jaroWinkler("chateau margaux", "chateau margaux") shouldBe 1.0
    }

    "return 1.0 for identical single-character strings" in {
      Similarity.jaroWinkler("a", "a") shouldBe 1.0
    }

    "return 0.0 for entirely disjoint strings" in {
      Similarity.jaroWinkler("abc", "xyz") shouldBe 0.0
    }

    "boost strings sharing a common prefix" in {
      val withPrefix    = Similarity.jaroWinkler("martha", "marhta")
      val withoutPrefix = Similarity.jaroWinkler("dwayne", "duane")
      withPrefix should be > 0.95
      withoutPrefix should (be > 0.8 and be < 0.9)
    }
  }

  "VivinoGateway.pickBestMatch" should {
    val target = WineName("Chateau Margaux")
    val year   = Some(Vintage(2020))

    def stats(
      avg: BigDecimal,
      count: Int,
      status: String = "Normal"
    ) =
      Some(VivinoGateway.AlgoliaStatistics(Some(count), Some(avg), Some(status)))

    def hit(
      name: String,
      year: Int,
      s: Option[VivinoGateway.AlgoliaStatistics],
      top: Option[VivinoGateway.AlgoliaStatistics] = None
    ) =
      VivinoGateway.AlgoliaHit(Some(name), top, Some(List(VivinoGateway.AlgoliaVintage(Some(year), s))))

    "pick the vintage whose year matches the target" in {
      val hits = List(
        hit("Chateau Margaux", 2019, stats(4.8, 5000)),
        hit("Chateau Margaux", 2020, stats(4.5, 1000)),
        hit("Chateau Margaux", 2021, stats(4.3, 300))
      )
      VivinoGateway.pickBestMatch(target, year, hits).map(_.rating.unwrap) shouldBe Some(BigDecimal(4.5))
    }

    "skip hits whose normalized name is below the similarity threshold" in {
      val hits = List(hit("Barolo Riserva", 2020, stats(4.5, 500)))
      VivinoGateway.pickBestMatch(target, year, hits) shouldBe None
    }

    "prefer the highest-similarity match when multiple hits share the year" in {
      val hits = List(
        hit("Chateau Margoux", 2020, stats(4.0, 100)), // close typo
        hit("Chateau Margaux", 2020, stats(4.5, 1000)), // exact
        hit("Chateux Margaux", 2020, stats(3.5, 50)) // close typo
      )
      VivinoGateway.pickBestMatch(target, year, hits).map(_.rating.unwrap) shouldBe Some(BigDecimal(4.5))
    }

    "tolerate missing optional fields" in {
      val hits = List(
        VivinoGateway.AlgoliaHit(None, None, None),
        VivinoGateway.AlgoliaHit(Some("Chateau Margaux"), None, None),
        VivinoGateway.AlgoliaHit(Some("Chateau Margaux"), None, Some(List.empty))
      )
      VivinoGateway.pickBestMatch(target, year, hits) shouldBe None
    }

    "skip vintages with non-Normal status" in {
      val hits = List(hit("Chateau Margaux", 2020, stats(4.8, 1000, status = "Hidden")))
      VivinoGateway.pickBestMatch(target, year, hits) shouldBe None
    }

    "skip ratings outside the 0-5 range" in {
      val hits = List(hit("Chateau Margaux", 2020, stats(BigDecimal(7), 1000)))
      VivinoGateway.pickBestMatch(target, year, hits) shouldBe None
    }

    "skip wines with zero ratings" in {
      val hits = List(hit("Chateau Margaux", 2020, stats(BigDecimal(0), 0)))
      VivinoGateway.pickBestMatch(target, year, hits) shouldBe None
    }

    "use the hit's top-level statistics when vintage is None" in {
      val nvTarget = WineName("Krug Grande Cuvée")
      val hits     = List(hit("Krug Grande Cuvee", 2020, stats(4.5, 5000), top = stats(4.4, 25000)))
      val result   = VivinoGateway.pickBestMatch(nvTarget, None, hits)
      result.map(_.rating.unwrap) shouldBe Some(BigDecimal(4.4))
      result.map(_.ratingsCount.unwrap) shouldBe Some(25000)
    }

    "ignore per-vintage stats for NV queries even if present" in {
      val nvTarget = WineName("Krug Grande Cuvée")
      // Only per-vintage stats — no top-level → NV query returns None.
      val hits = List(hit("Krug Grande Cuvee", 2020, stats(4.5, 5000)))
      VivinoGateway.pickBestMatch(nvTarget, None, hits) shouldBe None
    }
  }
}
