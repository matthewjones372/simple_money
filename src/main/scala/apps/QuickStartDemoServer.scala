package apps

import akka.http.scaladsl.Http
import com.danielasfregola.randomdatagenerator.RandomDataGenerator
import model.{Currency, CurrencyAccount}
import org.scalacheck.{Arbitrary, Gen}

import scala.concurrent.Await
import scala.concurrent.duration.Duration

object QuickStartDemoServer extends App  with RandomDataGenerator {

  def accountGenerator(n: Int): Seq[CurrencyAccount] = {
    implicit val arb: Arbitrary[String] = Arbitrary(Gen.alphaStr)
    random[CurrencyAccount](n)
  }

  val accounts: Seq[CurrencyAccount] = Vector(
    CurrencyAccount("Account1", 100, Currency.GBP),
    CurrencyAccount("Account2", 250, Currency.GBP),
    CurrencyAccount("Account3", 5637, Currency.GBP),
    CurrencyAccount("Account4", 573.53, Currency.GBP),
    CurrencyAccount("Account5", 250, Currency.GBP),
  )

  val app =
    new AppLoader(accounts ++ accountGenerator(5000))

  import app._

  Http().bindAndHandle(routes, host, port)
  logger.info(s"Server online at http://$host:$port/")
  Await.result(system.whenTerminated, Duration.Inf)
}
