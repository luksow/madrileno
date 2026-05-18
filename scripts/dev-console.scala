#!/usr/bin/env -S scala-cli shebang

//> using scala 3.8.2
//> using toolkit default

// Launches a scala-cli REPL with the project's runtime classpath, dropping
// you at a prompt where `app`, `run`, and `db` are already bound.
//
// Why not `sbt console`? — Scala 3 REPL under sbt can't resolve JDK
// platform-module classes like `java.net.http.HttpClient`, which the project's
// sttp4 backend needs. scala-cli's standalone REPL handles it correctly.
//
// Boot path:
//   1. Read the cached runtime classpath at `target/console-classpath`. This
//      file is written as a side-effect of the `update` task in `build.sbt`,
//      so any time deps resolve (cold sbt start, dep change, explicit
//      `sbt update`) the classpath is refreshed. If it's missing, the
//      script tells you to run `sbt update`.
//   2. Load `.env` into the subprocess environment so `ConsoleApplication.boot()`
//      sees the same `PG_*` / `APP_ENVIRONMENT` / etc. that the running app does.
//   3. Write the predef below to a temp file, exec `scala-cli repl --classpath
//      <cp> <predef>`. Top-level vals/defs in the predef show up at the REPL
//      prompt without needing `:load` or explicit imports.

// `import madrileno.main.*` etc. can't live as actual imports in this script —
// the wrapper compiles standalone with only the toolkit, no project classpath.
// They live inside this string and only execute in the spawned REPL JVM, which
// gets the project classpath on its command line.
private val predefSource: String =
  """import cats.effect.IO
    |import cats.effect.unsafe.implicits.global
    |import madrileno.main.*
    |import madrileno.auth.domain.*
    |import madrileno.user.domain.*
    |import madrileno.utils.db.transactor.DB
    |
    |// Free top-level statements aren't allowed in `.scala` files, so the banner
    |// folds into `app`'s initializer.
    |val app: ApplicationLoader = {
    |  val a = ConsoleApplication.boot()
    |  println(s"madrileno dev console — env=${a.appConfig.environment}")
    |  println("  app        the ApplicationLoader (transactor, repositories, services)")
    |  println("  run(io)    execute an IO[A] and return A")
    |  println("  db(action) execute a DB[A] inside a session")
    |  a
    |}
    |
    |def run[A](io: IO[A]): A    = io.unsafeRunSync()
    |def db[A](action: DB[A]): A = run(app.transactor.inSession(action))
    |""".stripMargin

@main def devConsole(): Unit = {
  val root          = os.pwd
  val classpathFile = root / "target" / "console-classpath"

  require(os.exists(root / "build.sbt"),
    s"$root doesn't look like the project root (no build.sbt)")
  if (!os.exists(classpathFile)) {
    Console.err.println(s"missing $classpathFile")
    Console.err.println("run `sbt update` first — that hook writes the cached classpath.")
    sys.exit(1)
  }

  val cp = os.read(classpathFile).trim
  require(cp.nonEmpty, s"$classpathFile is empty")

  val envFromFile: Map[String, String] = {
    val f = root / ".env"
    if (!os.exists(f)) Map.empty
    else os.read(f).linesIterator
      .map(_.trim)
      .filter(l => l.nonEmpty && !l.startsWith("#"))
      .flatMap { l =>
        l.split("=", 2) match {
          case Array(k, v) =>
            val unquoted = v.trim.stripPrefix("\"").stripSuffix("\"").stripPrefix("'").stripSuffix("'")
            Some(k.trim -> unquoted)
          case _ => None
        }
      }.toMap
  }

  val predefFile = os.temp(predefSource, prefix = "dev-console-predef-", suffix = ".scala")

  val rc = os.proc("scala-cli", "repl", "--scala", "3.8.2", "--classpath", cp, predefFile.toString).call(
    env = sys.env ++ envFromFile,
    stdin = os.Inherit,
    stdout = os.Inherit,
    stderr = os.Inherit,
    check = false
  ).exitCode
  sys.exit(rc)
}
