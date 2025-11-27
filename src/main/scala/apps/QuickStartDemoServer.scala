package apps

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.http.scaladsl.Http
import org.apache.pekko.http.scaladsl.server.Route
import org.apache.pekko.stream.{Materializer, SystemMaterializer}
import cats.Eval
import infrastructure.actors.AccountRoutes
import infrastructure.dataStores.InMemoryEvalDataStore
import infrastructure.loggers.EvalLogger
import service.AccountTransferService

import scala.concurrent.Await
import scala.concurrent.duration.Duration

object QuickStartDemoServer extends App with AccountRoutes {
  implicit val system: ActorSystem =
    ActorSystem("currencyAccountServer")

  implicit val materializer: Materializer =
    SystemMaterializer(system).materializer

  override implicit def executionContext: scala.concurrent.ExecutionContext = system.dispatcher

  lazy val dataStore: InMemoryEvalDataStore =
    new InMemoryEvalDataStore

  lazy val logger: EvalLogger = new EvalLogger

  lazy val transferService: AccountTransferService[Eval] =
    new AccountTransferService[Eval](dataStore, logger)

  val routes: Route = accountRoutes
  val port: Int     = 8081 // TODO: Load in from config file
  val host: String  = "0.0.0.0"

  Http().newServerAt(host, port).bindFlow(routes)

  logger.info(s"Server online at http://$host:$port/")
  Await.result(system.whenTerminated, Duration.Inf)
}
