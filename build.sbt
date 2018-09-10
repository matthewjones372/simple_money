lazy val akkaHttpVersion   = "10.0.11"
lazy val akkaVersion       = "2.5.11"
lazy val akkaCircieVersion = "1.21.0"
lazy val circieVersion     = "0.9.3"
lazy val scalaTestVersion  = "3.0.1"
lazy val catsVersion       = "1.3.1"

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
    "de.heikoseeberger"          %% "akka-http-circe"       % akkaCircieVersion,
    "io.circe"                   %% "circe-core"            % circieVersion,
    "io.circe"                   %% "circe-generic"         % circieVersion,
    "io.circe"                   %% "circe-parser"          % circieVersion,
    "io.circe"                   %% "circe-generic-extras"  % circieVersion,
    "org.typelevel"              %% "cats-core"             % catsVersion,
    "com.danielasfregola"        %% "random-data-generator" % "2.5",
    "ch.qos.logback"             % "logback-classic"        % "1.2.3",
    "com.typesafe.scala-logging" %% "scala-logging"         % "3.9.0",
    "com.typesafe"               % "config"                 % "1.3.2",
    "com.typesafe.akka"          %% "akka-http-testkit"     % akkaHttpVersion % Test,
    "org.scalatest"              %% "scalatest"             % scalaTestVersion % Test
  )
)

scalafmtOnCompile in ThisBuild := true

resolvers ++= Seq(
  Resolver.bintrayRepo("hseeberger", "maven"),
  Resolver.bintrayRepo("scalameta", "maven")
)
