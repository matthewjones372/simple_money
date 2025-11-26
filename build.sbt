lazy val pekkoHttpVersion = "1.1.0"
lazy val pekkoVersion = "1.1.0"
lazy val circeVersion = "0.14.7"
lazy val scalaTestVersion = "3.2.18"
lazy val catsVersion = "2.10.0"
lazy val logbackVersion = "1.4.14"
lazy val scalaLoggingVersion = "3.9.5"

lazy val root = (project in file(".")).settings(
  inThisBuild(
    List(
      organization := "com.matt",
      scalaVersion := "3.7.4"
    )
  ),
  name := "simple_money",
  libraryDependencies ++= Seq(
    "org.apache.pekko" %% "pekko-actor" % pekkoVersion,
    "org.apache.pekko" %% "pekko-stream" % pekkoVersion,
    "org.apache.pekko" %% "pekko-http" % pekkoHttpVersion,
    "com.softwaremill.sttp.tapir" %% "tapir-pekko-http-server" % "1.10.12",
    "com.softwaremill.sttp.tapir" %% "tapir-openapi-docs" % "1.10.12",
    "com.softwaremill.sttp.tapir" %% "tapir-swagger-ui-bundle" % "1.10.12",
    "com.softwaremill.sttp.tapir" %% "tapir-json-circe" % "1.10.12",
    "io.circe" %% "circe-parser" % circeVersion,
    "io.circe" %% "circe-generic" % circeVersion,
    "org.typelevel" %% "cats-core" % catsVersion,
    "ch.qos.logback" % "logback-classic" % logbackVersion,
    "com.typesafe.scala-logging" %% "scala-logging" % scalaLoggingVersion,
    "org.apache.pekko" %% "pekko-testkit" % pekkoVersion % Test,
    "org.apache.pekko" %% "pekko-http-testkit" % pekkoHttpVersion % Test,
    "org.scalatest" %% "scalatest" % scalaTestVersion % Test
  )
)

scalacOptions ++= Seq(
  "-deprecation",
  "-feature",
  "-unchecked",
  "-Xfatal-warnings"
)

// For Docker Packaging
dockerBaseImage := "eclipse-temurin:21-jre"
enablePlugins(AshScriptPlugin)
enablePlugins(JavaAppPackaging)
enablePlugins(DockerPlugin)
