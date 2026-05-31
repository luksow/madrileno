package madrileno.main

import org.reflections.Reflections
import org.reflections.scanners.Scanners
import org.scalatest.funspec.AnyFunSpec
import org.scalatest.matchers.should.Matchers

import scala.jdk.CollectionConverters.*
import scala.util.Try

// Catches "silent module wiring": a `*Module` trait gets defined under the project package, the file
// compiles, but ApplicationLoader never gets `with <Name>Module` added to its extends chain. The app
// boots, the module's routes / tasks never fire, no error is produced.
class ModuleWiringSpec extends AnyFunSpec with Matchers {

  // Module traits that intentionally exist but are NOT mixed into ApplicationLoader.
  // Add the fully qualified class name with a one-line reason.
  // If this grows past a few entries, prefer a marker annotation instead.
  private val intentionallyNotMixedIn: Set[String] = Set.empty

  describe("ApplicationLoader") {
    it("mixes in every *Module trait defined under the project package") {
      val pkg = classOf[ApplicationLoader].getPackage.getName.split('.').head // "madrileno"

      val reflections = new Reflections(pkg, Scanners.SubTypes.filterResultsBy(_ => true))
      val candidates  = reflections.getAll(Scanners.SubTypes).asScala.toSet

      val moduleTraits = candidates.filter { fqn =>
        fqn.startsWith(s"$pkg.") && fqn.split('.').last.endsWith("Module") && isProjectTrait(fqn)
      }

      val mixedIn = allInterfaces(classOf[ApplicationLoader]).map(_.getName)
      val missing = (moduleTraits diff mixedIn) diff intentionallyNotMixedIn

      withClue(buildClue(missing)) { missing shouldBe empty }
    }
  }

  private def isProjectTrait(fqn: String): Boolean =
    Try(Class.forName(fqn)).toOption.exists(c => c.isInterface && !c.isAnnotation)

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
          |If a module is intentionally excluded, add its FQCN to
          |ModuleWiringSpec.intentionallyNotMixedIn with a one-line reason.
          |""".stripMargin
}
