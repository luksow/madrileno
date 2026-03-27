addSbtPlugin("org.typelevel" % "sbt-tpolecat" % "0.5.3")

addSbtPlugin("com.github.sbt" % "sbt-native-packager" % "1.11.7")

addSbtPlugin("com.github.sbt" % "flyway-sbt" % "12.0.0")
libraryDependencies += "org.flywaydb" % "flyway-database-postgresql" % "12.0.1"

addSbtPlugin("org.scalameta" % "sbt-scalafmt" % "2.5.6")

addSbtPlugin("ch.epfl.scala" % "sbt-scalafix" % "0.14.6")

addSbtPlugin("io.spray" % "sbt-revolver" % "0.10.0")

addSbtPlugin("nl.gn0s1s" % "sbt-dotenv" % "3.2.0")

addSbtPlugin("com.timushev.sbt" % "sbt-rewarn" % "0.1.3")

addSbtPlugin("com.timushev.sbt" % "sbt-updates" % "0.6.4")
