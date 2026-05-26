#!/usr/bin/env -S scala-cli shebang

//> using scala 3.8.2
//> using jvm 21
//> using toolkit default
//> using dep com.softwaremill.chimp::core:0.1.8
//> using dep com.softwaremill.sttp.tapir::tapir-netty-server-sync:1.13.18

import chimp.*
import sttp.tapir.*
import sttp.tapir.server.netty.sync.NettySyncServer

import scala.util.{Failure, Success, Try}

case class MadrilenoRef(repo: String, ref: String)

case class OverviewInput()                                                                derives io.circe.Codec, Schema
case class ModuleInput(name: String)                                                      derives io.circe.Codec, Schema
case class DocInput(name: String)                                                         derives io.circe.Codec, Schema
case class SourceInput(path: String)                                                      derives io.circe.Codec, Schema
case class ChangesInput(since: Option[String], paths: Option[List[String]], target: Option[String]) derives io.circe.Codec, Schema

object MCPServer {

  // -- shadow clone management ------------------------------------------------

  val projectRoot: os.Path = os.pwd
  val shadowDir: os.Path   = projectRoot / ".madrileno-mcp" / "repo"

  // Read .madrileno-ref once on first access. The MCP's whole guarantee is "anchored to a specific
  // sha"; re-reading at every tool call would let runtime edits to .madrileno-ref drift the anchor
  // to a sha the shadow clone never fetched (since ensureShadow runs only at startup).
  lazy val readRef: Either[String, MadrilenoRef] = {
    val refFile = projectRoot / ".madrileno-ref"
    if (!os.exists(refFile))
      Left(s"missing $refFile — run `./scripts/init-project.scala` to generate it, or write it by hand")
    else {
      val map = os
        .read(refFile)
        .linesIterator
        .map(_.trim)
        .filter(l => l.nonEmpty && !l.startsWith("#"))
        .flatMap { l =>
          l.split("=", 2) match {
            case Array(k, v) => Some(k.trim -> v.trim)
            case _           => None
          }
        }
        .toMap
      (map.get("repo"), map.get("ref")) match {
        case (Some(repo), Some(ref)) => Right(MadrilenoRef(repo, ref))
        case _                       => Left(s"$refFile must contain `repo=<url>` and `ref=<sha>` lines")
      }
    }
  }

  // Read once on first access — same lazy-val rationale as readRef: the package dir doesn't
  // change at runtime, and re-listing the FS on every tool call is just waste.
  lazy val projectPackage: Option[String] = {
    val mainScala = projectRoot / "src" / "main" / "scala"
    if (!os.exists(mainScala)) None
    else
      os.list(mainScala).filter(os.isDir(_)) match {
        case Seq(single) => Some(single.last)
        case _           => None
      }
  }

  // Belt-and-braces against a `.madrileno-ref` whose `repo=` line is hand-edited to something
  // starting with `-` — git clone would interpret it as an option, since `clone` doesn't accept
  // `--` to disambiguate the URL. Local-path and standard-scheme URLs are both fine.
  private def validateRepoUrl(repo: String): Either[String, Unit] =
    if (repo.nonEmpty && !repo.startsWith("-")) Right(())
    else Left(s"`.madrileno-ref` repo URL is empty or starts with '-': '$repo'")

  // Tool inputs are AI-supplied; restrict to characters that compose into safe git paths.
  // Module/doc names are simple identifiers; refs may include `/`, `.`, `@`, `^`, `~` for branches/tags
  // but must be single refnames (no `..` ranges — the caller composes ranges by passing since/target separately).
  private val ValidNamePattern   = """[A-Za-z0-9_-]+""".r
  private val ValidRefPattern    = """[a-zA-Z0-9_./@^~-]+""".r
  private val PinnedShaPattern   = """[0-9a-fA-F]{7,40}""".r // accept short shas down to 7 chars; case-insensitive per git
  private def validateName(name: String): Either[String, Unit] =
    if (ValidNamePattern.matches(name)) Right(()) else Left(s"name must match [A-Za-z0-9_-]+; got '$name'")
  private def validateRef(ref: String): Either[String, Unit] =
    if (!ValidRefPattern.matches(ref)) Left(s"ref must match [a-zA-Z0-9_./@^~-]+; got '$ref'")
    else if (ref.startsWith("-")) Left(s"ref must not start with '-' (would be parsed as a git option); got '$ref'")
    else if (ref.contains("..")) Left(s"ref must be a single refname, not a range (no `..`); got '$ref'")
    else Right(())
  // Stricter than validateRef: the pinned ref in `.madrileno-ref` is supposed to be a commit SHA
  // (init-project writes one from `git rev-parse HEAD`). A branch/tag name there would silently
  // drift the anchor as upstream moves — violates the "anchored to a sha" contract.
  private def validatePinnedRef(ref: String): Either[String, Unit] =
    if (PinnedShaPattern.matches(ref)) Right(())
    else Left(s"pinned ref must be a commit SHA (7-40 hex chars); got '$ref'. Re-run init-project, or hand-edit .madrileno-ref to a sha.")

  def ensureShadow(ref: MadrilenoRef): Either[String, Unit] = {
    // Validate the pinned ref alongside the repo URL — `.madrileno-ref` is hand-edit-able,
    // and a `ref=-rf` would otherwise flow into `git show -rf:path` as an option.
    def syncShadow: Either[String, Unit] = Try {
      if (!os.exists(shadowDir)) {
        Console.err.println(s"[mcp] cloning ${ref.repo} -> $shadowDir (one-time, ~50MB)")
        os.makeDir.all(shadowDir / os.up)
        os.proc("git", "clone", ref.repo, shadowDir.toString).call()
      } else if (!os.exists(shadowDir / ".git")) {
        throw new IllegalStateException(s"$shadowDir exists but is not a git repo. Remove it and re-run.")
      } else {
        // If `.madrileno-ref`'s `repo=` changed since last run (e.g. user switched origin), the
        // existing shadow's `origin` still points at the old URL. Re-point it so the subsequent
        // fetch lands in the right place — otherwise we'd silently fetch from the wrong repo.
        val currentOrigin = os.proc("git", "-C", shadowDir.toString, "remote", "get-url", "origin").call(check = false).out.text().trim
        if (currentOrigin != ref.repo) {
          Console.err.println(s"[mcp] shadow's origin ($currentOrigin) differs from .madrileno-ref repo (${ref.repo}); re-pointing")
          os.proc("git", "-C", shadowDir.toString, "remote", "set-url", "origin", ref.repo).call()
        }
        Console.err.println(s"[mcp] fetching latest in $shadowDir")
        val r = os.proc("git", "-C", shadowDir.toString, "fetch", "origin").call(check = false)
        if (r.exitCode != 0)
          Console.err.println(s"[mcp] warning: `git fetch origin` failed (exit ${r.exitCode}); madrileno_changes will compare against the cached origin/main: ${r.err.text().trim}")
      }
    } match {
      case Success(_) => Right(())
      case Failure(e) => Left(s"shadow clone failed: ${e.getMessage}")
    }
    for {
      _ <- validateRepoUrl(ref.repo)
      _ <- validatePinnedRef(ref.ref).left.map(e => s"invalid pinned ref in .madrileno-ref: $e")
      _ <- syncShadow
    } yield ()
  }

  // -- git plumbing -----------------------------------------------------------

  def gitShow(ref: String, path: String): Either[String, String] = {
    val r = os.proc("git", "-C", shadowDir.toString, "show", s"$ref:$path").call(check = false)
    if (r.exitCode == 0) Right(r.out.text())
    else Left(s"git show $ref:$path failed: ${r.err.text().trim}")
  }

  def gitListTree(ref: String, path: String): Either[String, List[String]] = {
    // `--` separator: ref is a sha (no leading `-`) but path can come from a tool input
    // (`madrileno_module(name)` → `src/main/scala/madrileno/<name>`), so guard against a
    // path that starts with `-` being parsed as an ls-tree option.
    val r = os.proc("git", "-C", shadowDir.toString, "ls-tree", "-r", "--name-only", ref, "--", path).call(check = false)
    if (r.exitCode == 0) Right(r.out.text().linesIterator.toList.filter(_.nonEmpty))
    else Left(s"git ls-tree -r --name-only $ref -- $path failed: ${r.err.text().trim}")
  }

  def gitLog(since: String, target: String, paths: List[String]): Either[String, List[String]] = {
    for {
      _    <- validateRef(since)
      _    <- validateRef(target)
      _    <- paths.foldLeft(Right(()): Either[String, Unit])((acc, p) => acc.flatMap(_ => validatePath(p)))
      base  = Seq("git", "-C", shadowDir.toString, "log", "--oneline", "--no-decorate", s"$since..$target")
      args  = if (paths.nonEmpty) base ++ Seq("--") ++ paths else base
      r     = os.proc(args).call(check = false)
      logs <- if (r.exitCode == 0) Right(r.out.text().linesIterator.toList.filter(_.nonEmpty))
              else Left(s"git log $since..$target failed: ${r.err.text().trim}")
    } yield logs
  }

  // -- source rewriting -------------------------------------------------------

  def rewritePackage(content: String, userPackage: Option[String]): String = userPackage match {
    case Some(pkg) if pkg != "madrileno" =>
      // Only rewrite `madrileno.` qualifiers (package declarations + imports). Standalone
      // `madrileno` would also catch string literals, comments, and URLs (e.g. `luksow/madrileno`
      // in a doc link) — that's noise for source returned to Claude. Matches the overview's
      // "rewritten from `madrileno.*` to `<pkg>.*`" claim.
      content.replace("madrileno.", s"$pkg.")
    case _ => content
  }

  // -- tool handlers ----------------------------------------------------------

  def overview(): Either[String, String] = {
    for {
      ref     <- readRef
      modules <- gitListTree(ref.ref, "src/main/scala/madrileno").map(_.flatMap(extractModule).distinct.sorted)
      docs    <- gitListTree(ref.ref, "docs").map(_.filter(_.endsWith(".md")).map(_.stripPrefix("docs/").stripSuffix(".md")).sorted)
      scripts <- gitListTree(ref.ref, "scripts").map(
                   _.filter(p => p.endsWith(".scala") && !p.contains("/templates/"))
                     .map(_.stripPrefix("scripts/").stripSuffix(".scala"))
                     .sorted
                 )
    } yield {
      val pkg = projectPackage.getOrElse("<package>")
      s"""# Madrileno reference (anchored at ${ref.ref.take(10)})
         |
         |Madrileno is a Scala 3 backend template (http4s + stir + cats-effect + Skunk Postgres). The codebase you (the AI) are helping write is derived from it; this MCP serves the upstream reference at a pinned commit.
         |
         |## Implementing a new module — start with the scaffold
         |
         |**Run `./scripts/scaffold-module.scala <Aggregate> <plural>` first.** It generates the canonical layout — domain + repository + service + router + DTO + module trait + Flyway migration + three specs (domain, repository, router) — auto-wires `ApplicationLoader`, and bakes in conventions that aren't obvious from reading source. Edit the generated files to add your domain-specific fields. Skipping the scaffold means rediscovering those conventions by hand; most are easy to miss.
         |
         |Before editing, read `madrileno_doc("principles")`, `madrileno_doc("module-anatomy")`, `madrileno_doc("adding-a-module")` and `madrileno_doc("testing-guide")` — they cover the non-obvious bits the scaffold doesn't reveal on its own.
         |
         |## Reference modules
         |
         |Each module is a vertical slice. Call `madrileno_module(name)` for the concatenated source (main + tests) of any:
         |
         |${modules.map(m => s"- $m").mkString("\n")}
         |
         |`user` is the simplest (single aggregate, no relations). `auction` (if present) is the richest (multi-aggregate, soft-delete, events, scheduler, S3 images). Pick the one that matches what you're building.
         |
         |## Docs
         |
         |Call `madrileno_doc(name)` for any of:
         |
         |${docs.map(d => s"- $d").mkString("\n")}
         |
         |Start with `adding-a-module` for vertical-slice walkthroughs, `domain-modeling` for opaque-type / validation idioms, `testing-guide` for the testing setup the scaffold's specs build on.
         |
         |## Scripts
         |
         |Operational scripts under `scripts/`. Call `madrileno_source("scripts/<name>.scala")` for the verbatim source of any:
         |
         |${scripts.map(s => s"- $s").mkString("\n")}
         |
         |`scaffold-module` is the one you reach for when adding new aggregates (see top of this overview).
         |
         |## Available tools
         |
         |- `madrileno_overview()` — this. Call first.
         |- `madrileno_module(name)` — all source files for one module (main + tests).
         |- `madrileno_doc(name)` — one doc.
         |- `madrileno_source(path)` — verbatim file. Fallback for a specific path.
         |- `madrileno_changes(since?, paths?, target?)` — git log between two refs, optionally path-filtered. Defaults: `since=${ref.ref.take(10)}` (the pinned ref), `target=origin/main`. Use this to learn what's new in upstream since your project was anchored.
         |
         |## Package rewriting
         |
         |The user's project package is `$pkg`. Source returned by `madrileno_module` and `madrileno_source` is automatically rewritten: `madrileno.*` -> `$pkg.*`. Docs are returned verbatim.
         |""".stripMargin
    }
  }

  private def extractModule(path: String): Option[String] = {
    val prefix = "src/main/scala/madrileno/"
    if (path.startsWith(prefix)) {
      val rest = path.substring(prefix.length)
      val seg  = rest.split("/").headOption
      seg.filter(s => s != "main" && s != "utils")
    } else None
  }

  def module(name: String): Either[String, String] = {
    for {
      _          <- validateName(name)
      ref        <- readRef
      mainPaths  <- gitListTree(ref.ref, s"src/main/scala/madrileno/$name").map(_.filter(_.endsWith(".scala")))
      // git ls-tree returns 0 with empty output when the path doesn't exist (e.g. a module without
      // tests), so an empty list here is "no test dir, fine" — non-zero exits are real errors and propagate.
      testPaths  <- gitListTree(ref.ref, s"src/test/scala/madrileno/$name").map(_.filter(_.endsWith(".scala")))
      allPaths    = mainPaths ++ testPaths
      _          <- if (allPaths.isEmpty) Left(s"no files under src/{main,test}/scala/madrileno/$name at ref ${ref.ref.take(10)}") else Right(())
      contents   <- allPaths.foldLeft(Right(List.empty[(String, String)]): Either[String, List[(String, String)]]) { (acc, p) =>
                      for {
                        soFar <- acc
                        body  <- gitShow(ref.ref, p)
                      } yield (p, body) :: soFar
                    }
      ordered     = contents.reverse
      pkg         = projectPackage
    } yield {
      ordered
        .map { case (p, body) =>
          val rewritten = rewritePackage(body, pkg)
          s"// ===== $p =====\n$rewritten"
        }
        .mkString("\n\n")
    }
  }

  def doc(name: String): Either[String, String] = {
    for {
      _       <- validateName(name)
      ref     <- readRef
      content <- gitShow(ref.ref, s"docs/$name.md")
    } yield content
  }

  def source(path: String): Either[String, String] = {
    for {
      _       <- validatePath(path)
      ref     <- readRef
      content <- gitShow(ref.ref, path)
    } yield rewritePackage(content, projectPackage)
  }

  // Source paths are AI-supplied. Reject the obvious traversal / option-injection shapes;
  // git's own resolution stays within the shadow clone so this is about returning clean
  // validation errors rather than confusing git errors, not about security.
  private def validatePath(path: String): Either[String, Unit] =
    if (path.isEmpty) Left("path must be non-empty")
    else if (path.startsWith("-")) Left(s"path must not start with '-'; got '$path'")
    else if (path.split("/").contains("..")) Left(s"path must not contain '..' segments; got '$path'")
    else if (path.contains(":")) Left(s"path must not contain ':' (would split the `ref:path` revspec in `git show`); got '$path'")
    else Right(())

  def changes(since: Option[String], paths: Option[List[String]], target: Option[String]): Either[String, String] = {
    for {
      ref       <- readRef
      sinceRef   = since.getOrElse(ref.ref)
      targetRef  = target.getOrElse("origin/main")
      logs      <- gitLog(sinceRef, targetRef, paths.getOrElse(Nil))
    } yield {
      if (logs.isEmpty) s"no commits in $sinceRef..$targetRef${paths.filter(_.nonEmpty).map(p => s" for paths ${p.mkString(", ")}").getOrElse("")}"
      else s"$sinceRef..$targetRef (${logs.size} commit${if (logs.size == 1) "" else "s"}):\n\n" + logs.mkString("\n")
    }
  }

  // -- chimp wiring -----------------------------------------------------------

  val overviewTool = tool("madrileno_overview")
    .description("Returns orientation: what madrileno is, available reference modules, doc index, available tools, pinned ref. CALL THIS FIRST in any session — it's the anchor.")
    .input[OverviewInput]
    .handle(_ => overview())

  val moduleTool = tool("madrileno_module")
    .description("Returns concatenated source of all main + test files under one reference module (e.g., 'user', 'auction', 'healthcheck'). Files are prefixed with `// ===== <path> =====`. Source is auto-rewritten from `madrileno.*` to the local project package. CALL THIS to learn a module pattern in full when implementing a similar one.")
    .input[ModuleInput]
    .handle(i => module(i.name))

  val docTool = tool("madrileno_doc")
    .description("Returns one doc (markdown) at the pinned ref. Pass the basename without extension, e.g. 'auth', 'database', 'adding-a-module'. Returned verbatim (no package rewriting).")
    .input[DocInput]
    .handle(i => doc(i.name))

  val sourceTool = tool("madrileno_source")
    .description("Returns the verbatim source of any file at the pinned ref. Pass a full path like `src/main/scala/madrileno/user/repositories/UserRepository.scala`. Source is auto-rewritten from `madrileno.*` to the local project package. Fallback for when `madrileno_module` is too coarse.")
    .input[SourceInput]
    .handle(i => source(i.path))

  val changesTool = tool("madrileno_changes")
    .description("Returns `git log --oneline` between two refs. Defaults: `since` = the project's pinned ref, `target` = `origin/main`, no path filter. Use to learn what's changed in upstream madrileno since the project was anchored.")
    .input[ChangesInput]
    .handle(i => changes(i.since, i.paths, i.target))

  @main def serve(): Unit = {
    // Sanity-check the cwd is a project root — `os.pwd` is what `projectRoot`, `shadowDir`,
    // and `readRef` all resolve against. Running this from `scripts/` (e.g. via
    // `scala-cli run scripts/mcp-server.scala`) would otherwise produce a misleading
    // "missing .madrileno-ref" error.
    if (!os.exists(projectRoot / "build.sbt")) {
      Console.err.println(s"[mcp] $projectRoot doesn't look like a project root (no build.sbt). Run this from the project root.")
      sys.exit(1)
    }
    val ref = readRef match {
      case Right(r) => r
      case Left(e)  => Console.err.println(s"[mcp] $e"); sys.exit(1)
    }
    ensureShadow(ref) match {
      case Right(_) => ()
      case Left(e)  => Console.err.println(s"[mcp] $e"); sys.exit(1)
    }
    Console.err.println(s"[mcp] anchored at ${ref.ref.take(10)} (${ref.repo})")
    Console.err.println(s"[mcp] listening on http://localhost:8080/mcp")

    val mcpServerEndpoint = mcpEndpoint(
      List(overviewTool, moduleTool, docTool, sourceTool, changesTool),
      List("mcp")
    )

    // Bind to localhost explicitly — there's no auth, so binding to 0.0.0.0 would expose the
    // MCP endpoints on the LAN. Loopback only; users wanting LAN access can edit this.
    NettySyncServer().host("127.0.0.1").port(8080).addEndpoint(mcpServerEndpoint).startAndWait()
  }
}
