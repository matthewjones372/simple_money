lazy val tapirVersion = "1.13.3"
lazy val pekkoHttpVersion = "1.3.0"
lazy val pekkoVersion = "1.4.0"
lazy val circeVersion = "0.14.15"
lazy val scalaTestVersion = "3.2.19"
lazy val catsVersion = "2.13.0"
lazy val logbackVersion = "1.5.22"
lazy val scalaLoggingVersion = "3.9.6"

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
    "org.apache.pekko" %% "pekko-slf4j" % pekkoVersion,
    "org.apache.pekko" %% "pekko-http" % pekkoHttpVersion,
    "com.softwaremill.sttp.tapir" %% "tapir-core" % tapirVersion,
    "com.softwaremill.sttp.tapir" %% "tapir-pekko-http-server" % tapirVersion,
    "com.softwaremill.sttp.tapir" %% "tapir-json-circe" % tapirVersion,
    "com.softwaremill.sttp.tapir" %% "tapir-swagger-ui-bundle" % tapirVersion,
    "io.circe" %% "circe-parser" % circeVersion,
    "io.circe" %% "circe-generic" % circeVersion,
    "org.typelevel" %% "cats-core" % catsVersion,
    "ch.qos.logback" % "logback-classic" % logbackVersion,
    "com.typesafe.scala-logging" %% "scala-logging" % scalaLoggingVersion,
    "org.apache.pekko" %% "pekko-testkit" % pekkoVersion % Test,
    "org.apache.pekko" %% "pekko-http-testkit" % pekkoHttpVersion % Test,
    "org.scalatest" %% "scalatest" % scalaTestVersion % Test
  ),
  dependencyOverrides ++= Seq(
    "org.apache.pekko" %% "pekko-actor" % pekkoVersion,
    "org.apache.pekko" %% "pekko-stream" % pekkoVersion,
    "org.apache.pekko" %% "pekko-slf4j" % pekkoVersion,
    "org.apache.pekko" %% "pekko-protobuf-v3" % pekkoVersion,
    "org.apache.pekko" %% "pekko-testkit" % pekkoVersion,
    "org.apache.pekko" %% "pekko-http" % pekkoHttpVersion,
    "org.apache.pekko" %% "pekko-http-core" % pekkoHttpVersion,
    "org.apache.pekko" %% "pekko-parsing" % pekkoHttpVersion,
    "org.apache.pekko" %% "pekko-http-testkit" % pekkoHttpVersion
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
