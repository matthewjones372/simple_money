package apps

import akka.actor.{ ActorRef, ActorSystem }
import akka.http.scaladsl.server.Route
import akka.stream.ActorMaterializer
import cats.Eval
import infrastructure.actors.{ AccountActor, AccountRoutes }
import infrastructure.dataStores.InMemoryEvalDataStore
import infrastructure.loggers.EvalLogger
import model.CurrencyAccount
import service.AccountTransferService

class AppLoader(givenAccounts: Seq[CurrencyAccount]) extends AccountRoutes {
  implicit val system: ActorSystem =
    ActorSystem("currencyAccountServer")

  implicit val materializer: ActorMaterializer =
    ActorMaterializer()

  lazy val dataStore: InMemoryEvalDataStore =
    new InMemoryEvalDataStore(givenAccounts)

  lazy val logger: EvalLogger =
    new EvalLogger

  lazy val transferService: AccountTransferService[Eval] =
    new AccountTransferService[Eval](dataStore, logger)

  val currencyAccountActor: ActorRef =
    system.actorOf(AccountActor.props(transferService), "currentAccountProps")

  val routes: Route = accountRoutes
  val port: Int     = 8081 // TODO: Load in from config file
  val host: String  = "localhost"
}
