package madrileno.main

import org.reflections.Reflections
import org.reflections.scanners.Scanners
import org.scalatest.funspec.AnyFunSpec
import org.scalatest.matchers.should.Matchers

import scala.jdk.CollectionConverters.*
import scala.util.Try

class ModuleWiringSpec extends AnyFunSpec with Matchers {

  // FQCN → reason.
  private val intentionallyNotMixedIn: Map[String, String] = Map.empty

  describe("ApplicationLoader") {
    it("mixes in every *Module trait defined under the project package") {
      // Convention: ApplicationLoader lives in `<root>.main` — drop the trailing segment.
      val pkg = classOf[ApplicationLoader].getPackage.getName.split('.').dropRight(1).mkString(".")

      // filterResultsBy(_ => true) keeps java.lang.Object entries; without it, traits extending only Object are missed.
      val reflections = new Reflections(pkg, Scanners.SubTypes.filterResultsBy(_ => true))
      val candidates  = reflections.getAll(Scanners.SubTypes).asScala.toSet

      val moduleTraits = candidates.filter { fqn =>
        fqn.startsWith(s"$pkg.") && fqn.split('.').last.endsWith("Module") && isProjectTrait(fqn)
      }

      val mixedIn = allInterfaces(classOf[ApplicationLoader]).map(_.getName)
      val missing = (moduleTraits diff mixedIn) diff intentionallyNotMixedIn.keySet

      withClue(buildClue(missing)) { missing shouldBe empty }
    }
  }

  private val loader = classOf[ApplicationLoader].getClassLoader

  private def isProjectTrait(fqn: String): Boolean =
    Try(Class.forName(fqn, false, loader)).toOption.exists(c => c.isInterface && !c.isAnnotation)

  private def allInterfaces(c: Class[?]): Set[Class[?]] = {
    val direct        = c.getInterfaces.toSet
    val viaInterfaces = direct.flatMap(allInterfaces)
    val viaSuper      = Option(c.getSuperclass).map(allInterfaces).getOrElse(Set.empty)
    direct ++ viaInterfaces ++ viaSuper
  }

  private def buildClue(missing: Set[String]): String =
    if (missing.isEmpty) ""
    else
      s"""|
          |Module trait(s) defined under the project package but not mixed into ApplicationLoader:
          |${missing.toSeq.sorted.map(m => s"  - $m").mkString("\n")}
          |
          |Fix: add `with <ModuleName>` to ApplicationLoader's extends chain.
          |If a module is intentionally excluded, add an entry to
          |ModuleWiringSpec.intentionallyNotMixedIn (FQCN → reason).
          |""".stripMargin
}
