package apps

import zio.*
import zio.test.*

object SimpleMoneySpec extends ZIOSpecDefault:

  def portFrom(settings: Map[String, String]): IO[Config.Error, Int] =
    ZIO.config(SimpleMoney.portConfig).withConfigProvider(ConfigProvider.fromMap(settings))

  def spec = suite("SimpleMoneySpec")(
    test("listens on 8081 when no port is configured") {
      portFrom(Map.empty).map(port => assertTrue(port == 8081))
    },
    test("listens on the configured port") {
      portFrom(Map("port" -> "9000")).map(port => assertTrue(port == 9000))
    },
    test("refuses a port that is not a number") {
      portFrom(Map("port" -> "eighty")).either.map(result => assertTrue(result.isLeft))
    }
  )
