lazy val akkaHttpVersion   = "10.1.1"
lazy val akkaVersion       = "2.5.16"
lazy val akkaCircieVersion = "1.21.0"
lazy val circieVersion     = "0.9.3"
lazy val scalaTestVersion  = "3.0.1"
lazy val catsVersion       = "1.0.1"

lazy val root = (project in file(".")).settings(
  inThisBuild(
    List(
      organization := "com.matt",
      scalaVersion := "2.12.6"
    )
  ),
  name := "simple_money",
  libraryDependencies ++= Seq(
    "com.typesafe.akka"          %% "akka-http"             % akkaHttpVersion,
    "de.heikoseeberger"          %% "akka-http-circe"       % akkaCircieVersion ,
    "io.circe"                   %% "circe-generic"         % circieVersion,
    "io.circe"                   %% "circe-parser"          % circieVersion,
    "io.circe"                   %% "circe-generic-extras"  % circieVersion,
    "org.typelevel"              %% "cats-core"             % catsVersion,
    "ch.qos.logback"             % "logback-classic"        % "1.2.3",
    "com.typesafe.scala-logging" %% "scala-logging"         % "3.9.0",
    "com.typesafe.akka"          %% "akka-http-testkit"     % akkaHttpVersion % Test,
    "org.scalatest"              %% "scalatest"             % scalaTestVersion % Test
  )
)
scalacOptions += "-deprecation"


dockerBaseImage := "openjdk:jre-alpine"
enablePlugins(AshScriptPlugin)
enablePlugins(JavaAppPackaging)
enablePlugins(DockerPlugin)

resolvers ++= Seq(
  Resolver.bintrayRepo("hseeberger", "maven"),
  Resolver.bintrayRepo("scalameta", "maven")
)
