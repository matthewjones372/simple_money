package apps

import akka.actor.{ ActorRef, ActorSystem }
import akka.http.scaladsl.Http
import akka.http.scaladsl.server.Route
import akka.stream.ActorMaterializer
import cats.Eval
import com.danielasfregola.randomdatagenerator.RandomDataGenerator
import infrastructure.actors.{ AccountActor, AccountRoutes }
import infrastructure.dataStores.InMemoryEvalDataStore
import infrastructure.loggers.EvalLogger
import model.{ Currency, CurrencyAccount }
import org.scalacheck.{ Arbitrary, Gen }
import service.AccountTransferService

import scala.concurrent.Await
import scala.concurrent.duration.Duration

object QuickStartServer extends App with AccountRoutes with RandomDataGenerator {

  def accountGenerator(n: Int): Seq[CurrencyAccount] = {
    implicit val arb = Arbitrary(Gen.alphaStr)
    random[CurrencyAccount](n)
  }

  implicit val system: ActorSystem             = ActorSystem("currencyAccountServer")
  implicit val materializer: ActorMaterializer = ActorMaterializer()

  val accounts: Seq[CurrencyAccount] = Vector(
    CurrencyAccount("Account1", 100, Currency.GBP),
    CurrencyAccount("Account2", 250, Currency.GBP),
    CurrencyAccount("Account3", 5637, Currency.GBP),
    CurrencyAccount("Account4", 573.53, Currency.GBP),
    CurrencyAccount("Account5", 250, Currency.GBP),
  )

  val dataStore: InMemoryEvalDataStore =
    new InMemoryEvalDataStore(accountGenerator(10000) ++ accounts)

  val logger: EvalLogger =
    new EvalLogger

  val transferService: AccountTransferService[Eval] =
    new AccountTransferService[Eval](dataStore, logger)

  val currencyAccountActor: ActorRef =
    system.actorOf(AccountActor.props(transferService), "currentAccountProps")

  val routes: Route = accountRoutes
  val port          = 8081
  val host          = "localhost"
  Http().bindAndHandle(routes, host, port)

  logger.info(s"Server online at http://$host:$port/")

  Await.result(system.whenTerminated, Duration.Inf)
}
