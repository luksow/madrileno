#!/usr/bin/env -S scala-cli shebang

//> using scala 3.6
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

    val packageName = packageOpt.getOrElse(name.toLowerCase.replaceAll("[^a-z0-9]", ""))
    require(packageName.matches("[a-z][a-z0-9]*"),
      s"package '$packageName' is not a lowercase Scala identifier (derived from '$name'; pass --package explicitly)")
    require(name.nonEmpty, "project name must not be empty")

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
    val auctionBlock = """(?s)[ \t]*// scripts:auction-block-start.*?// scripts:auction-block-end[ \t]*\r?\n?""".r
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
          .replaceAll("""\bmadrileno\b""", name)
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
    println("Next: sbt compile")
  }

  def main(args: Array[String]): Unit = ParserForMethods(this).runOrExit(args)
}
