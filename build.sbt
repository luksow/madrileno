name := "madrileno"
organization := "pl.iterators"
version := "1.0.0-SNAPSHOT"

scalaVersion := "3.8.2"

libraryDependencies ++= {
  val http4sV            = "0.23.34"
  val http4sStirV        = "0.4.1"
  val http4sOtelV        = "0.17.0"
  val circeV             = "0.14.15"
  val pureconfigV        = "0.17.10"
  val sttpV              = "4.0.23"
  val skunkV             = "1.0.0"
  val postgresqlV        = "42.7.10"
  val catsV              = "2.13.0"
  val catsEffectV        = "3.7.0"
  val catsEffectTestingV = "1.8.0"
  val chimneyV           = "1.9.0"
  val kebsV              = "2.1.5"
  val logbackV           = "1.5.32"
  val log4catsV          = "2.8.0"
  val otel4sV            = "0.16.0"
  val otelV              = "1.61.0"
  val otelLogbackV       = "2.27.0-alpha"
  val macwireV           = "2.6.7"
  val sealedV            = "2.0.1"
  val jwtCoreV           = "11.0.3"
  val firebaseV          = "9.8.0"
  val cron4sV            = "0.8.2"
  val jakartaMailV       = "2.1.3"
  val angusMailV         = "2.0.3"
  val scalatagsV         = "0.13.1"
  val scaffeineV         = "5.3.0"
  val testcontainersV    = "0.44.1"
  val baklavaV           = "1.3.0"
  val swaggerUiV         = "5.21.0"
  val flywayV            = "12.0.1"
  val scalatestV         = "3.2.20"
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
    "org.tpolecat"                    %% "skunk-circe"                               % skunkV,
    "org.postgresql"                   % "postgresql"                                % postgresqlV,
    "io.scalaland"                    %% "chimney"                                   % chimneyV,
    "org.typelevel"                   %% "cats-core"                                 % catsV,
    "org.typelevel"                   %% "cats-effect"                               % catsEffectV,
    "pl.iterators"                    %% "kebs-http4s-stir"                          % kebsV,
    "pl.iterators"                    %% "kebs-circe"                                % kebsV,
    "pl.iterators"                    %% "kebs-opaque"                               % kebsV,
    "pl.iterators"                    %% "kebs-instances"                            % kebsV,
    "pl.iterators"                    %% "sealed-monad"                              % sealedV,
    "com.github.jwt-scala"            %% "jwt-core"                                  % jwtCoreV,
    "com.github.alonsodomin.cron4s"   %% "cron4s-core"                               % cron4sV,
    "jakarta.mail"                     % "jakarta.mail-api"                          % jakartaMailV,
    "org.eclipse.angus"                % "angus-mail"                                % angusMailV         % Runtime,
    "com.lihaoyi"                     %% "scalatags"                                 % scalatagsV,
    "com.github.blemale"              %% "scaffeine"                                 % scaffeineV,
    "com.google.firebase"              % "firebase-admin"                            % firebaseV,
    "pl.iterators"                    %% "baklava-http4s-routes"                     % baklavaV,
    "org.webjars"                      % "swagger-ui"                                % swaggerUiV,
    "pl.iterators"                    %% "http4s-stir-testkit"                       % http4sStirV        % "test",
    "pl.iterators"                    %% "baklava-http4s"                            % baklavaV           % "test",
    "pl.iterators"                    %% "baklava-scalatest"                         % baklavaV           % "test",
    "pl.iterators"                    %% "baklava-openapi"                           % baklavaV           % "test",
    "pl.iterators"                    %% "baklava-simple"                            % baklavaV           % "test",
    "pl.iterators"                    %% "baklava-tsrest"                            % baklavaV           % "test",
    "pl.iterators"                    %% "kebs-baklava"                              % kebsV              % "test",
    "com.dimafeng"                    %% "testcontainers-scala-scalatest"            % testcontainersV    % "test",
    "com.dimafeng"                    %% "testcontainers-scala-postgresql"           % testcontainersV    % "test",
    "org.flywaydb"                     % "flyway-core"                               % flywayV            % "test",
    "org.flywaydb"                     % "flyway-database-postgresql"                % flywayV            % "test",
    "org.scalatest"                   %% "scalatest"                                 % scalatestV         % "test",
    "org.typelevel"                   %% "cats-effect-testkit"                       % catsEffectV        % "test",
    "org.typelevel"                   %% "cats-effect-testing-scalatest"             % catsEffectTestingV % "test"
  )
}
Test / scalacOptions ++= Seq("-Wconf:msg=should not be used as infix:s", "-Wconf:msg=unused value of type org.scalatest:s")

javaOptions += "-Dotel.java.global-autoconfigure.enabled=true"

Compile / run / fork := true

// native-packager
enablePlugins(JavaServerAppPackaging, BaklavaSbtPlugin)
import com.typesafe.sbt.packager.docker.Cmd
dockerCommands += Cmd("ARG", "BUILD_VERSION")
dockerCommands += Cmd("ENV", "APP_VERSION=$BUILD_VERSION")
dockerRepository := sys.env.get("DOCKER_REPO")
packageName := "madrileno"
dockerBaseImage := "azul/zulu-openjdk:21"
Docker / daemonUser := "noroot"
dockerUpdateLatest := true

// baklava
inConfig(Test)(
  BaklavaSbtPlugin.settings(Test) ++ Seq(
    fork := false,
    baklavaGenerateConfigs := Map(
      "openapi-info" ->
        s"""
          |{
          |  "openapi" : "3.0.1",
          |  "info" : {
          |    "title" : "${name.value}"
          |  }
          |}
          |""".stripMargin,
      "ts-rest-package-contract-json" ->
        s"""
          |{
          |  "name": "@madrileno-dev/${name.value}-contracts",
          |  "version": "${version.value}",
          |  "main": "index.js",
          |  "types": "index.d.ts"
          |}
          |""".stripMargin
    )
  )
)

// flyway
enablePlugins(FlywayPlugin)
flywayUrl := s"jdbc:postgresql://${sys.env.getOrElse("PG_HOST", "localhost")}:${sys.env.getOrElse("PG_PORT", "5432")}/${sys.env.getOrElse("PG_DATABASE", "madrileno")}"
flywayUser := sys.env.getOrElse("PG_USER", "postgres")
flywayPassword := sys.env.getOrElse("PG_PASSWORD", "postgres")

// scalafix
semanticdbEnabled := true
semanticdbVersion := scalafixSemanticdb.revision

lazy val verifyAll = taskKey[Unit]("Performs all verifications to assure that the build will pass CI checks.")
Test / verifyAll := {
  Def.sequential(Compile / scalafmtSbtCheck, Compile / scalafmtCheckAll, Compile / compile, Test / test).value
}
