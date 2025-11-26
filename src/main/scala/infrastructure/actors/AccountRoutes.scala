package infrastructure.actors

import org.apache.pekko.actor.{ActorRef, ActorSystem}
import org.apache.pekko.event.Logging
import org.apache.pekko.http.scaladsl.model.HttpResponse
import org.apache.pekko.http.scaladsl.server.Directives.{pathPrefix, _}
import org.apache.pekko.http.scaladsl.server.Route
import org.apache.pekko.http.scaladsl.server.directives.MethodDirectives.get
import org.apache.pekko.http.scaladsl.server.directives.RouteDirectives.complete
import org.apache.pekko.pattern.ask
import org.apache.pekko.util.Timeout
import domain.model.CurrencyAccount
import infrastructure.actors.AccountActor._
import infrastructure.actors.CirceSupport

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._

trait AccountRoutes extends CirceSupport with JsonCodecs {

  implicit def system: ActorSystem

  implicit def executionContext: ExecutionContext = system.dispatcher

  lazy val log = Logging(system, classOf[AccountRoutes])

  def currencyAccountActor: ActorRef

  implicit val timeout: Timeout = Timeout(5.seconds)

  lazy val accountRoutes: Route = {
    pathPrefix("api" / "accounts" / "transfer") {
      (pathEndOrSingleSlash & put) {
        entity(as[TransferBetweenAccounts]) { transferRequest =>
          complete((currencyAccountActor ? transferRequest).mapTo[HttpResponse])
        }
      }
    } ~ pathPrefix("api" / "accounts") {
      pathEndOrSingleSlash {
        get {
          val accounts = (currencyAccountActor ? GetAccounts).mapTo[Seq[CurrencyAccount]]
          complete(accounts)
        } ~ post {
          entity(as[PostNewAccount]) { newAccount =>
            val result = currencyAccountActor ? newAccount
            complete(result.mapTo[HttpResponse])
          }
        }
      }
    }
  }
}
