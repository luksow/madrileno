#!/usr/bin/env -S scala-cli shebang

//> using scala 3.8.2
//> using jvm 21
//> using toolkit default
//> using dep com.lihaoyi::mainargs:0.7.8

// Rename this project from `madrileno` to `<name>`, swap the package, and delete
// the auction demo. Run from the project root, either directly:
//
//   ./scripts/init-project.scala wine-cellar
//   ./scripts/init-project.scala wine-cellar --package winecellar
//
// or via scala-cli:
//
//   scala-cli run scripts/init-project.scala -- wine-cellar
//
// After: `sbt compile`. If anything's off, `git checkout .` reverts.

import mainargs.{ParserForMethods, arg, main}

import java.util.regex.Matcher

object InitProject {

  // Directories we never descend into. Build caches, IDE state, logs, this scripts dir.
  private val SkipDirs: Set[String] =
    Set(".git", "target", ".bsp", ".bloop", ".scala-build", ".metals", ".idea", ".vscode", "logs", "node_modules", "scripts")

  // Files we never read as text (would corrupt or fail).
  private val BinaryExts: Set[String] =
    Set("jpg", "jpeg", "png", "gif", "ico", "jar", "class", "tasty", "woff", "woff2", "ttf", "otf", "pdf", "zip", "tar", "gz", "bin")

  @main
  def run(
    @arg(positional = true, doc = "Project name (e.g. 'wine-cellar')")
    name: String,
    @arg(name = "package", doc = "Scala package; defaults to project name lowercased with non-alphanumerics stripped")
    packageOpt: Option[String] = None
  ): Unit = {

    require(name.matches("[A-Za-z][A-Za-z0-9_-]*"),
      s"project name '$name' must start with a letter and contain only letters, digits, '-' and '_' (sbt + HOCON + docker-compose all share that bar)")
    val packageName = packageOpt.getOrElse(name.toLowerCase.replaceAll("[^a-z0-9]", ""))
    require(packageName.matches("[a-z][a-z0-9]*"),
      s"package '$packageName' is not a lowercase Scala identifier (derived from '$name'; pass --package explicitly)")

    val root = os.pwd
    require(os.exists(root / "build.sbt"),
      s"$root doesn't look like an sbt project (no build.sbt)")
    require(os.exists(root / "src" / "main" / "scala" / "madrileno"),
      "no src/main/scala/madrileno tree found — already renamed?")

    // 1. Delete the auction demo: source, tests, related Flyway migrations.
    val deleted = scala.collection.mutable.ListBuffer.empty[os.Path]
    for {
      kind    <- Seq("main", "test")
      auction = root / "src" / kind / "scala" / "madrileno" / "auction"
      if os.exists(auction)
    } {
      os.remove.all(auction)
      deleted += auction
    }
    val migrations = root / "src" / "main" / "resources" / "db" / "migration"
    if (os.exists(migrations)) {
      os.list(migrations)
        .filter(p => p.last.toLowerCase.contains("auction") || p.last.toLowerCase.contains("bid"))
        .foreach { p =>
          os.remove(p)
          deleted += p
        }
    }

    // 2. Rewrite text files. Order matters: do the auction surgery while the strings
    //    still say `madrileno.auction`, then run the package + standalone-name renames.
    //    Auction surgery:
    //      a) drop `import madrileno.auction.<X>` lines
    //      b) drop `with AuctionModule` lines from the loaders' extends chains
    //      c) drop blocks bracketed by `// scripts:auction-block-start` / `-end`
    //         (used in test support — TestData, TestApplicationLoader)
    // Markers must occupy their own line (with optional indentation). Anchoring keeps
    // the regex from eating prose in docs that mentions the marker strings inline.
    val auctionBlock = """(?ms)^[ \t]*// scripts:auction-block-start[ \t]*\r?\n.*?^[ \t]*// scripts:auction-block-end[ \t]*\r?\n?""".r
    val touched = scala.collection.mutable.ListBuffer.empty[os.Path]
    os.walk(root)
      .filter(p => os.isFile(p, followLinks = false))
      .filter(p => !p.relativeTo(root).segments.exists(SkipDirs.contains))
      .filter(p => !BinaryExts.contains(p.ext.toLowerCase))
      .foreach { p =>
        val original = os.read(p)
        val transformed = auctionBlock
          .replaceAllIn(original, "")
          .replaceAll("""(?m)^import madrileno\.auction\..*\r?\n""", "")
          .replaceAll("""(?m)^[ \t]*with AuctionModule[ \t]*\r?\n""", "")
          .replace("madrileno.", s"$packageName.")
          .replaceAll("""\bmadrileno\b""", Matcher.quoteReplacement(name))
        if (transformed != original) {
          os.write.over(p, transformed)
          touched += p
        }
      }

    // 3. Rename the source directories.
    for {
      kind <- Seq("main", "test")
      old  = root / "src" / kind / "scala" / "madrileno"
      if os.exists(old)
    } {
      os.move(old, root / "src" / kind / "scala" / packageName)
    }

    println(s"Project: $name")
    println(s"Package: $packageName")
    println(s"Deleted: ${deleted.size} auction-related paths")
    println(s"Updated: ${touched.size} files")
    println()

    // Auction surgery leaves orphaned imports (e.g., `utils.imaging.*`, `utils.storage.StorageKey`,
    // `org.http4s.MediaType`) in shared files like TestData.scala. scalafix's RemoveUnused +
    // OrganizeImports.removeUnused clears them. Two passes because removing one import can
    // make another unused (cascading). One-time cost on init; bundling it here means the user
    // gets a green `sbt test` on the very next command.
    println("Running scalafix to clean up auction-leftover imports (this takes a minute on first run)...")
    val scalafix = os.proc("sbt", "scalafixAll", "scalafixAll").call(cwd = root, check = false, stdout = os.Inherit, stderr = os.Inherit)
    if (scalafix.exitCode != 0) {
      println()
      println("⚠ scalafix didn't complete cleanly. The rename + auction deletion still applied; you can re-run scalafix manually:")
      println("  sbt 'scalafixAll; scalafixAll'")
    }

    println()
    println("Next:")
    println("  cp .env.sample .env")
    println("  sbt test")
  }

  def main(args: Array[String]): Unit = ParserForMethods(this).runOrExit(args)
}
