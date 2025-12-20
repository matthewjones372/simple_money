package apps

import org.apache.pekko.actor.{ActorRef, ActorSystem}
import org.apache.pekko.http.scaladsl.Http
import org.apache.pekko.http.scaladsl.server.Route
import org.apache.pekko.stream.{Materializer, SystemMaterializer}
import cats.Eval
import infrastructure.actors.{AccountActor, AccountRoutes}
import infrastructure.endpoints.AccountTapirRoutes
import infrastructure.dataStores.InMemoryEvalDataStore
import infrastructure.loggers.EvalLogger
import service.AccountTransferService

import scala.concurrent.Await
import scala.concurrent.duration.Duration

object QuickStartDemoServer extends AccountTapirRoutes {
  implicit lazy val system: ActorSystem =
    ActorSystem("currencyAccountServer")

  implicit lazy val materializer: Materializer =
    SystemMaterializer(system).materializer

  override implicit lazy val executionContext: scala.concurrent.ExecutionContext = system.dispatcher

  lazy val dataStore: InMemoryEvalDataStore =
    new InMemoryEvalDataStore

  lazy val logger: EvalLogger = new EvalLogger

  lazy val transferService: AccountTransferService[Eval] =
    new AccountTransferService[Eval](dataStore, logger)

  lazy val currencyAccountActor: ActorRef =
    system.actorOf(AccountActor.props(transferService), "CurrencyAccountActor")

  lazy val routes: Route = tapirRoutes
  val port: Int     = 8081 // TODO: Load in from config file
  val host: String  = "0.0.0.0"

  def main(args: Array[String]): Unit = {
    Http().newServerAt(host, port).bind(routes)

    logger.info(s"Server online at http://localhost:$port/")
    logger.info(s"Swagger UI available at http://localhost:$port/docs")
    Await.result(system.whenTerminated, Duration.Inf)
  }
}
