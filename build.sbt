lazy val zioVersion = "2.1.26"
lazy val zioHttpVersion = "3.11.6"
lazy val logbackVersion = "1.6.5"
lazy val zioLoggingVersion = "2.5.3"

lazy val root = (project in file(".")).settings(
  inThisBuild(
    List(
      organization := "com.matt",
      scalaVersion := "3.10.0"
    )
  ),
  name := "simple_money",
  libraryDependencies ++= Seq(
    "dev.zio" %% "zio" % zioVersion,
    "dev.zio" %% "zio-http" % zioHttpVersion,
    "dev.zio" %% "zio-logging" % zioLoggingVersion,
    "dev.zio" %% "zio-logging-slf4j2" % zioLoggingVersion,
    "ch.qos.logback" % "logback-classic" % logbackVersion,
    "dev.zio" %% "zio-test" % zioVersion % Test,
    "dev.zio" %% "zio-test-sbt" % zioVersion % Test
  ),
  testFrameworks += new TestFramework("zio.test.sbt.ZTestFramework")
)

scalacOptions ++= Seq(
  "-deprecation",
  "-feature",
  "-unchecked",
  "-Werror",
  "-java-output-version",
  "25"
)

// For Docker Packaging
dockerBaseImage := "eclipse-temurin:25-jre"
enablePlugins(AshScriptPlugin)
enablePlugins(JavaAppPackaging)
enablePlugins(DockerPlugin)
