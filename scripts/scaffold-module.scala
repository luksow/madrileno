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
// plus domain / repository / router specs. Wires the module into
// ApplicationLoader's extends chain.

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

    val singular      = aggregate.head.toLower.toString + aggregate.tail
    val capitalPlural = plural.head.toUpper.toString + plural.tail
    // Singular and plural may legitimately be identical in English (`Fish fish`,
    // `News news`) — the singular drives Scala identifiers / directories, the
    // plural drives SQL/URL identifiers, so there's no actual collision.

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

    // Class-name collision check — covers the case where the singular directory
    // doesn't collide (e.g. `HealthCheck health_checks` -> `healthCheck/`) but the
    // generated `<Aggregate>Module` class name already exists elsewhere (e.g. the
    // built-in `HealthCheckModule`), which would leave `ApplicationLoader` with
    // ambiguous duplicate `with <X>Module` clauses on different packages.
    val moduleClassFile = s"${aggregate}Module.scala"
    val existingModuleFile = os.walk(mainScalaDir).find(_.last == moduleClassFile)
    require(existingModuleFile.isEmpty,
      s"module class '${aggregate}Module' already exists at ${existingModuleFile.getOrElse("")}. " +
        "Choose a different aggregate name to avoid an ambiguous `with` in ApplicationLoader.")

    // Table-name collision check — scan existing migrations for `CREATE TABLE <plural>`.
    // The Flyway runner would catch this eventually, but catching it here keeps the
    // failure on the script side (no files written, no migration shipped).
    val createTableRegex =
      s"""(?i)CREATE\\s+TABLE\\s+(IF\\s+NOT\\s+EXISTS\\s+)?"?${java.util.regex.Pattern.quote(plural)}"?\\s*\\(""".r
    val tableCollision = os.list(migrationDest).filter(os.isFile(_)).find { f =>
      createTableRegex.findFirstIn(os.read(f)).isDefined
    }
    require(tableCollision.isEmpty,
      s"plural '$plural' would collide with a table already created in ${tableCollision.getOrElse("")}. " +
        "Choose a different plural.")

    // Preflight the auto-wire anchors before any writes — if `ApplicationLoader.scala`
    // is missing or has been restructured, we want to abort before stranding generated
    // files on disk (a half-finished scaffold blocks the next run via the dest-exists
    // guards above and forces the user to clean up by hand).
    val loader = mainScalaDir / packageName / "main" / "ApplicationLoader.scala"
    require(os.exists(loader), s"missing $loader (expected the loader at this path)")
    val loaderText   = os.read(loader)
    val importAnchor = s"import $packageName.healthcheck.HealthCheckModule"
    require(loaderText.contains(importAnchor),
      s"can't auto-wire: import anchor '$importAnchor' not found in $loader.\n" +
        s"  Add `import $packageName.$singular.${aggregate}Module` manually.")
    val withAnchor = "    with HealthCheckModule"
    require(loaderText.contains(withAnchor),
      s"can't auto-wire: extends-clause anchor '$withAnchor' not found in $loader.\n" +
        s"  Add `with ${aggregate}Module` to the extends chain manually.")

    // Next Flyway version — max V<N>__ + 1. Filter to files only so a subdirectory
    // (Flyway supports a `vendor` subfolder) can't accidentally satisfy the regex.
    val nextVersion = os.list(migrationDest).filter(os.isFile(_)).map(_.last).flatMap { f =>
      """^V(\d+)__""".r.findFirstMatchIn(f).map(_.group(1).toInt)
    }.maxOption.getOrElse(0) + 1

    // Placeholders are non-overlapping (the trailing `__` on each token means neither
    // `__aggregate__` nor `__Aggregate__` is a substring of its plural form), so order
    // is currently cosmetic — but keep longer-before-shorter as a habit for any future
    // placeholder additions where it might start mattering.
    val subs = List(
      ("__Aggregates__", capitalPlural),
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
    // clause anchored on HealthCheckModule (already validated above). Insertion isn't
    // alphabetically sorted — scalafix (which the next-step printout recommends)
    // reorders the imports afterwards.

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
