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
  val http4sV            = "0.23.30"
  val http4sStirV        = "0.4.0"
  val http4sOtelV        = "0.10.0"
  val circeV             = "0.14.10"
  val pureconfigV        = "0.17.8"
  val sttpV              = "4.0.0-RC1"
  val skunkV             = "1.0.0-M10"
  val catsV              = "2.13.0"
  val catsEffectV        = "3.5.7"
  val catsEffectTestingV = "1.6.0"
  val chimneyV           = "1.7.3"
  val kebsV              = "2.0.0"
  val logbackV           = "1.5.16"
  val log4catsV          = "2.7.0"
  val otel4sV            = "0.11.2"
  val otelV              = "1.47.0"
  val otelLogbackV       = "2.12.0-alpha"
  val macwireV           = "2.6.6"
  val sealedV            = "2.0.0"
  val jwtCoreV           = "10.0.4"
  val firebaseV          = "9.4.3"
  val scalatestV         = "3.2.19"
  Seq(
    "org.http4s"                      %% "http4s-ember-server"                       % http4sV,
    "org.http4s"                      %% "http4s-dsl"                                % http4sV,
    "org.http4s"                      %% "http4s-circe"                              % http4sV,
    "pl.iterators"                    %% "http4s-stir"                               % http4sStirV,
    "org.http4s"                      %% "http4s-otel4s-middleware-core"             % http4sOtelV,
    "org.http4s"                      %% "http4s-otel4s-middleware-metrics"          % http4sOtelV,
    "org.http4s"                      %% "http4s-otel4s-middleware-trace-core"       % http4sOtelV,
    "org.http4s"                      %% "http4s-otel4s-middleware-trace-server"     % http4sOtelV,
    "io.circe"                        %% "circe-core"                                % circeV,
    "ch.qos.logback"                   % "logback-classic"                           % logbackV,
    "org.typelevel"                   %% "log4cats-slf4j"                            % log4catsV,
    "org.typelevel"                   %% "otel4s-oteljava"                           % otel4sV,
    "io.opentelemetry"                 % "opentelemetry-exporter-otlp"               % otelV              % Runtime,
    "io.opentelemetry"                 % "opentelemetry-sdk-extension-autoconfigure" % otelV              % Runtime,
    "io.opentelemetry.instrumentation" % "opentelemetry-logback-appender-1.0"        % otelLogbackV,
    "com.softwaremill.macwire"        %% "macros"                                    % macwireV           % "provided",
    "com.softwaremill.macwire"        %% "proxy"                                     % macwireV,
    "com.softwaremill.macwire"        %% "util"                                      % macwireV,
    "com.github.pureconfig"           %% "pureconfig-core"                           % pureconfigV,
    "com.github.pureconfig"           %% "pureconfig-ip4s"                           % pureconfigV,
    "com.github.pureconfig"           %% "pureconfig-generic-scala3"                 % pureconfigV,
    "com.softwaremill.sttp.client4"   %% "fs2"                                       % sttpV,
    "com.softwaremill.sttp.client4"   %% "circe"                                     % sttpV,
    "com.softwaremill.sttp.client4"   %% "opentelemetry-backend"                     % sttpV,
    "org.tpolecat"                    %% "skunk-core"                                % skunkV,
    "io.scalaland"                    %% "chimney"                                   % chimneyV,
    "org.typelevel"                   %% "cats-core"                                 % catsV,
    "org.typelevel"                   %% "cats-effect"                               % catsEffectV,
    "pl.iterators"                    %% "kebs-http4s-stir"                          % kebsV,
    "pl.iterators"                    %% "kebs-circe"                                % kebsV,
    "pl.iterators"                    %% "kebs-opaque"                               % kebsV,
    "pl.iterators"                    %% "kebs-instances"                            % kebsV,
    "pl.iterators"                    %% "sealed-monad"                              % sealedV,
    "com.github.jwt-scala"            %% "jwt-core"                                  % jwtCoreV,
    "com.google.firebase"              % "firebase-admin"                            % firebaseV,
    "pl.iterators"                    %% "http4s-stir-testkit"                       % http4sStirV        % "test",
    "org.scalatest"                   %% "scalatest"                                 % scalatestV         % "test",
    "org.typelevel"                   %% "cats-effect-testkit"                       % catsEffectV        % "test",
    "org.typelevel"                   %% "cats-effect-testing-scalatest"             % catsEffectTestingV % "test"
  )
}
javaOptions += "-Dotel.java.global-autoconfigure.enabled=true"

Compile / run / fork := true

// native-packager
enablePlugins(JavaServerAppPackaging)
import com.typesafe.sbt.packager.docker.Cmd
dockerCommands += Cmd("ARG", "BUILD_VERSION")
dockerCommands += Cmd("ENV", "APP_VERSION=$BUILD_VERSION")
dockerRepository := sys.env.get("DOCKER_REPO")
packageName := "madrileno"
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
