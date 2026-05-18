#!/usr/bin/env -S scala-cli shebang

//> using scala 3.8.2
//> using toolkit default
//> using dep com.lihaoyi::mainargs:0.7.8

// Scaffold a new module (aggregate vertical slice) under the project's package.
// Run from the project root, either directly:
//
//   ./scripts/scaffold-module.scala Wine wines
//
// or via scala-cli:
//
//   scala-cli run scripts/scaffold-module.scala -- Wine wines
//
// Generates: module trait, domain, repository, service, router, DTO, migration,
// repo spec. Wires the module into ApplicationLoader's extends chain.

import mainargs.{ParserForMethods, arg, main}

object ScaffoldModule {

  @main
  def run(
    @arg(positional = true, doc = "Aggregate name in PascalCase, e.g. Wine") aggregate: String,
    @arg(positional = true, doc = "Plural lowercase, e.g. wines") plural: String
  ): Unit = {
    require(aggregate.matches("[A-Z][A-Za-z0-9]*"),
      s"aggregate '$aggregate' must be PascalCase (start with uppercase, alphanumeric only)")
    require(plural.matches("[a-z][a-z0-9_]*"),
      s"plural '$plural' must be lowercase (start with letter, alphanumeric or underscore)")

    val singular = aggregate.head.toLower.toString + aggregate.tail
    require(singular != plural,
      s"plural '$plural' must differ from singular '$singular' (otherwise table/dir naming collides)")

    val root = os.pwd
    require(os.exists(root / "build.sbt"),
      s"$root doesn't look like the project root (no build.sbt)")

    val mainScalaDir = root / "src" / "main" / "scala"
    require(os.exists(mainScalaDir), s"missing $mainScalaDir")

    val packageDirs = os.list(mainScalaDir).filter(os.isDir(_))
    require(packageDirs.size == 1,
      s"expected exactly one package directory under src/main/scala/, found: ${packageDirs.map(_.last).mkString(", ")}")
    val packageName = packageDirs.head.last

    val templates = root / "scripts" / "templates" / "module"
    require(os.exists(templates),
      s"missing $templates — the scaffold templates are bundled with the script")

    val mainDest      = mainScalaDir / packageName / singular
    val testDest      = root / "src" / "test" / "scala" / packageName / singular
    val migrationDest = root / "src" / "main" / "resources" / "db" / "migration"

    require(!os.exists(mainDest), s"$mainDest already exists — refusing to overwrite")
    require(!os.exists(testDest), s"$testDest already exists — refusing to overwrite")
    require(os.exists(migrationDest), s"missing $migrationDest")

    // Next Flyway version — max V<N>__ + 1.
    val nextVersion = os.list(migrationDest).map(_.last).flatMap { f =>
      """^V(\d+)__""".r.findFirstMatchIn(f).map(_.group(1).toInt)
    }.maxOption.getOrElse(0) + 1

    // Substitution order matters: PascalCase first (unambiguous), plural before
    // singular (otherwise singular eats the plural prefix), package last.
    val subs = List(
      ("__Aggregate__", aggregate),
      ("__aggregates__", plural),
      ("__aggregate__", singular),
      ("__package__", packageName)
    )

    def substitute(s: String): String =
      subs.foldLeft(s) { case (acc, (key, value)) => acc.replace(key, value) }

    def copyTree(srcDir: os.Path, destDir: os.Path): List[os.Path] = {
      os.walk(srcDir).filter(os.isFile(_)).map { src =>
        val rel          = src.relativeTo(srcDir)
        val destSegments = rel.segments.map(substitute)
        val dest         = destSegments.foldLeft(destDir)((acc, seg) => acc / seg)
        os.makeDir.all(dest / os.up)
        os.write(dest, substitute(os.read(src)))
        dest
      }.toList
    }

    val writtenMain = copyTree(templates / "main", mainDest)
    val writtenTest = copyTree(templates / "test", testDest)

    // Migration: each template SQL becomes V<next>__<substituted-name>.sql.
    val writtenMigration = os.list(templates / "migration").filter(os.isFile(_)).map { src =>
      val baseName = substitute(src.last)
      val dest     = migrationDest / s"V${nextVersion}__$baseName"
      os.write(dest, substitute(os.read(src)))
      dest
    }.toList

    // Auto-wire: insert both the `import` line and the `with <Aggregate>Module`
    // clause anchored on HealthCheckModule (always present in the framework's
    // stock loader). Insertion isn't alphabetically sorted — scalafmt/scalafix
    // (which the script's next-step printout recommends) reorder afterwards.
    // Fail loudly if either anchor is missing rather than silently no-op.
    val loader = mainScalaDir / packageName / "main" / "ApplicationLoader.scala"
    require(os.exists(loader), s"missing $loader (expected the loader at this path)")
    val loaderText = os.read(loader)

    val importAnchor = s"import $packageName.healthcheck.HealthCheckModule"
    require(loaderText.contains(importAnchor),
      s"can't auto-wire: import anchor '$importAnchor' not found in $loader.\n" +
        s"  Add `import $packageName.$singular.${aggregate}Module` manually.")
    val withAnchor = "    with HealthCheckModule"
    require(loaderText.contains(withAnchor),
      s"can't auto-wire: extends-clause anchor '$withAnchor' not found in $loader.\n" +
        s"  Add `with ${aggregate}Module` to the extends chain manually.")

    val newImport     = s"import $packageName.$singular.${aggregate}Module\n"
    val newWith       = s"    with ${aggregate}Module\n"
    val updatedLoader = loaderText
      .replace(importAnchor, newImport + importAnchor)
      .replace(withAnchor, newWith + withAnchor)
    require(updatedLoader != loaderText, "auto-wire substitution produced no change (unexpected)")
    os.write.over(loader, updatedLoader)

    println(s"Aggregate: $aggregate (singular '$singular', plural '$plural')")
    println(s"Package:   $packageName")
    println(s"Wrote ${writtenMain.size} main files under $mainDest")
    println(s"Wrote ${writtenTest.size} test files under $testDest")
    println(s"Wrote ${writtenMigration.size} migration(s) starting at V$nextVersion")
    println(s"Wired ${aggregate}Module into $loader")
    println()
    println("Next:")
    println("  sbt 'compile; scalafmtAll; scalafixAll'   # verify, format, sort the auto-wired import")
  }

  def main(args: Array[String]): Unit = ParserForMethods(this).runOrExit(args)
}
