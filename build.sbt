name := "madrileno"
organization := "pl.iterators"
version := "1.0.0-SNAPSHOT"

scalaVersion := "3.5.0"

scalacOptions ++= Seq(
  "-release:17",
  "-deprecation",
  "-encoding",
  "utf-8",
  "-unchecked",
  "-no-indent",
  "-Wunused:all",
  "-Wvalue-discard",
  "-Wnonunit-statement",
  if (insideCI.value || !sys.env.get("NO_FATAL_WARNING").contains("true")) "-Wconf:any:error" else "-Wconf:any:warning"
)

resolvers += "Sonatype OSS Snapshots" at "https://oss.sonatype.org/content/repositories/snapshots"

libraryDependencies ++= {
  val http4sV                = "0.23.27"
  val http4sStirV            = "0.3"
  val circeV                 = "0.14.9"
  val pureconfigV            = "0.17.7"
  val sttpV                  = "4.0.0-M17"
  val skunkV                 = "1.0.0-M7"
  val otel4sCoreV            = "0.8.0"
  val catsV                  = "2.12.0"
  val catsEffectV            = "3.5.4"
  val catsEffectTestingV     = "1.5.0"
  val chimneyV               = "1.4.0"
  val kebsV                  = "1.9.7+41-daad478e-SNAPSHOT"
  val logbackV               = "1.5.7"
  val macwireV               = "2.5.9"
  val sealedV                = "1.3.0"
  val jwtCoreV               = "10.0.1"
  val scalatestV             = "3.2.19"
  Seq(
    "org.http4s"                    %% "http4s-ember-server"           % http4sV,
    "org.http4s"                    %% "http4s-dsl"                    % http4sV,
    "org.http4s"                    %% "http4s-circe"                  % http4sV,
    "pl.iterators"                  %% "http4s-stir"                   % http4sStirV,
    "io.circe"                      %% "circe-core"                    % circeV,
    "ch.qos.logback"                 % "logback-classic"               % logbackV,
    "com.softwaremill.macwire"      %% "macros"                        % macwireV           % "provided",
    "com.softwaremill.macwire"      %% "proxy"                         % macwireV,
    "com.softwaremill.macwire"      %% "util"                          % macwireV,
    "com.github.pureconfig"         %% "pureconfig-core"               % pureconfigV,
    "com.github.pureconfig"         %% "pureconfig-ip4s"               % pureconfigV,
    "com.github.pureconfig"         %% "pureconfig-generic-scala3"     % pureconfigV,
    "com.softwaremill.sttp.client4" %% "fs2"                           % sttpV,
    "com.softwaremill.sttp.client4" %% "circe"                         % sttpV,
    "com.softwaremill.sttp.client4" %% "slf4j-backend"                 % sttpV,
    "org.tpolecat"                  %% "skunk-core"                    % skunkV,
    "org.typelevel"                 %% "otel4s-core"                   % otel4sCoreV,
    "io.scalaland"                  %% "chimney"                       % chimneyV,
    "org.typelevel"                 %% "cats-core"                     % catsV,
    "org.typelevel"                 %% "cats-effect"                   % catsEffectV,
    "pl.iterators"                  %% "kebs-http4s-stir"              % kebsV,
    "pl.iterators"                  %% "kebs-circe"                    % kebsV,
    "pl.iterators"                  %% "kebs-opaque"                   % kebsV,
    "pl.iterators"                  %% "kebs-instances"                % kebsV,
    "pl.iterators"                  %% "sealed-monad"                  % sealedV,
    "com.github.jwt-scala"          %% "jwt-core"                      % jwtCoreV,
    "pl.iterators"                  %% "http4s-stir-testkit"           % http4sStirV        % "test",
    "org.scalatest"                 %% "scalatest"                     % scalatestV         % "test",
    "org.typelevel"                 %% "cats-effect-testkit"           % catsEffectV        % "test",
    "org.typelevel"                 %% "cats-effect-testing-scalatest" % catsEffectTestingV % "test"
  )
}

Compile / run / fork := true

// native-packager
enablePlugins(JavaServerAppPackaging)
import com.typesafe.sbt.packager.docker.Cmd
dockerCommands += Cmd("ARG", "BUILD_VERSION")
dockerCommands += Cmd("ENV", "APP_VERSION=$BUILD_VERSION")
dockerRepository := sys.env.get("DOCKER_REPO")
packageName := "madrid"
dockerBaseImage := "azul/zulu-openjdk:17.0.4.1"
Docker / daemonUser := "noroot"
dockerUpdateLatest := true

// flyway
enablePlugins(FlywayPlugin)

// scalafmt
Test / scalafmtOnCompile := true
ThisBuild / scalafmtOnCompile := true

// scalafix
semanticdbEnabled := true
semanticdbVersion := scalafixSemanticdb.revision

lazy val verifyAll = taskKey[Unit]("Performs all verifications to assure that the build will pass CI checks.")
Test / verifyAll := {
  Def.sequential(Compile / scalafmtSbtCheck, Compile / scalafmtCheckAll, Compile / compile, Test / test).value
}

ThisBuild / evictionErrorLevel := Level.Warn // TODO: remove