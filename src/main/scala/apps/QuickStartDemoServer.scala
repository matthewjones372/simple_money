package apps

import akka.actor.{ActorRef, ActorSystem}
import akka.http.scaladsl.Http
import akka.http.scaladsl.server.Route
import akka.stream.ActorMaterializer
import cats.Eval
import infrastructure.actors.{AccountActor, AccountRoutes}
import infrastructure.dataStores.InMemoryEvalDataStore
import infrastructure.loggers.EvalLogger
import service.AccountTransferService

import scala.concurrent.Await
import scala.concurrent.duration.Duration

object QuickStartDemoServer extends App with AccountRoutes{
  implicit val system: ActorSystem =
    ActorSystem("currencyAccountServer")

  implicit val materializer: ActorMaterializer =
    ActorMaterializer()

  lazy val dataStore: InMemoryEvalDataStore =
    new InMemoryEvalDataStore

  lazy val logger: EvalLogger =
    new EvalLogger

  lazy val transferService: AccountTransferService[Eval] =
    new AccountTransferService[Eval](dataStore, logger)

  val currencyAccountActor: ActorRef =
    system.actorOf(AccountActor.props(transferService), "currentAccountProps")

  val routes: Route = accountRoutes
  val port: Int     = 8081 // TODO: Load in from config file
  val host: String  = "localhost"

  Http().bindAndHandle(routes, host, port)
  logger.info(s"Server online at http://$host:$port/")
  Await.result(system.whenTerminated, Duration.Inf)
}
