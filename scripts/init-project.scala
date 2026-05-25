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

import mainargs.{Flag, ParserForMethods, arg, main}

import java.util.regex.Matcher

object InitProject {

  // Directories we never descend into. Build caches, IDE state, logs, this scripts dir.
  private val SkipDirs: Set[String] =
    Set(".git", "target", ".bsp", ".bloop", ".scala-build", ".metals", ".idea", ".vscode", "logs", "node_modules", "scripts")

  // Files we never read as text (would corrupt or fail).
  private val BinaryExts: Set[String] =
    Set("jpg", "jpeg", "png", "gif", "ico", "jar", "class", "tasty", "woff", "woff2", "ttf", "otf", "pdf", "zip", "tar", "gz", "bin")

  private val MadrilenoUpstream = "https://github.com/luksow/madrileno.git"

  @main
  def run(
    @arg(positional = true, doc = "Project name (e.g. 'wine-cellar')")
    name: String,
    @arg(name = "package", doc = "Scala package; defaults to project name lowercased with non-alphanumerics stripped")
    packageOpt: Option[String] = None,
    @arg(name = "keep-docs", doc = "Keep the `docs/` tree (default: deleted — MCP server serves them from the pinned ref)")
    keepDocs: Flag = Flag()
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

    // 4. Pin the upstream madrileno ref so the MCP server knows which commit to anchor to.
    //    Read sha from this checkout's git HEAD — assumes the user is running init-project
    //    from a fresh clone of madrileno, which is the documented workflow.
    val sha = os.proc("git", "-C", root.toString, "rev-parse", "HEAD").call(check = false).out.trim()
    if (sha.nonEmpty) {
      os.write.over(root / ".madrileno-ref", s"repo=$MadrilenoUpstream\nref=$sha\n")
    } else {
      Console.err.println("warning: couldn't read git HEAD; skipping .madrileno-ref. Write it by hand if you want the MCP server to anchor to a specific upstream sha.")
    }

    // 5. .gitignore: add `.madrileno-mcp/` (the shadow clone the MCP server keeps for serving docs/source).
    val gitignore = root / ".gitignore"
    if (os.exists(gitignore)) {
      val current = os.read(gitignore)
      if (!current.linesIterator.exists(_.trim == ".madrileno-mcp/")) {
        os.write.over(gitignore, current + (if (current.endsWith("\n")) "" else "\n") + "\n# MCP server shadow clone\n.madrileno-mcp/\n")
      }
    }

    // 6. Delete the docs unless --keep-docs. The MCP server serves them on demand from
    //    the pinned ref, so most projects don't need to carry them locally.
    val docsRoot     = root / "docs"
    val docsDeleted  = !keepDocs.value && os.exists(docsRoot)
    if (docsDeleted) {
      os.remove.all(docsRoot)
    }

    println(s"Project: $name")
    println(s"Package: $packageName")
    println(s"Deleted: ${deleted.size} auction-related paths${if (docsDeleted) ", plus docs/ (run with --keep-docs to retain)" else ""}")
    println(s"Updated: ${touched.size} files")
    if (sha.nonEmpty) println(s"Anchored: $MadrilenoUpstream @ ${sha.take(10)} (see .madrileno-ref)")
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
    println("  ./scripts/mcp-server.scala &           # optional — start the docs/source MCP for Claude (see docs/mcp.md)")
  }

  def main(args: Array[String]): Unit = ParserForMethods(this).runOrExit(args)
}
