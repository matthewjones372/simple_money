lazy val zioVersion = "2.1.14"
lazy val zioHttpVersion = "3.0.1"
lazy val logbackVersion = "1.5.22"

lazy val root = (project in file(".")).settings(
  inThisBuild(
    List(
      organization := "com.matt",
      scalaVersion := "3.7.4"
    )
  ),
  name := "simple_money",
  libraryDependencies ++= Seq(
    "dev.zio" %% "zio" % zioVersion,
    "dev.zio" %% "zio-streams" % zioVersion,
    "dev.zio" %% "zio-http" % zioHttpVersion,
    "dev.zio" %% "zio-http-gen" % zioHttpVersion,
    "dev.zio" %% "zio-logging" % "2.4.0",
    "dev.zio" %% "zio-logging-slf4j2" % "2.4.0",
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
  "-Xfatal-warnings"
)

// For Docker Packaging
dockerBaseImage := "eclipse-temurin:21-jre"
enablePlugins(AshScriptPlugin)
enablePlugins(JavaAppPackaging)
enablePlugins(DockerPlugin)
