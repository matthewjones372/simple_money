package infrastructure.actors

import akka.actor.{ ActorRef, ActorSystem }
import akka.event.Logging
import akka.http.scaladsl.server.Directives.{ pathPrefix, _ }
import akka.http.scaladsl.server.Route
import akka.http.scaladsl.server.directives.MethodDirectives.get
import akka.http.scaladsl.server.directives.RouteDirectives.complete
import akka.pattern.ask
import akka.util.Timeout
import de.heikoseeberger.akkahttpcirce.FailFastCirceSupport
import domain.model.CurrencyAccount
import infrastructure.actors.AccountActor._

import scala.concurrent.duration._

trait AccountRoutes extends FailFastCirceSupport with JsonCodecs {

  implicit def system: ActorSystem

  lazy val log = Logging(system, classOf[AccountRoutes])

  def currencyAccountActor: ActorRef

  implicit val timeout: Timeout = Timeout(5.seconds)

  lazy val accountRoutes: Route = {
    pathPrefix("api" / "accounts" / "transfer") {
      (pathEndOrSingleSlash & put) {
        entity(as[transferBetweenAccounts]) { transferRequest =>
          complete((currencyAccountActor ? transferRequest).mapTo[ActionPerformed])
        }
      }
    } ~ pathPrefix("api" / "accounts") {
      pathEndOrSingleSlash {
        get {
          val accounts = (currencyAccountActor ? GetAccounts).mapTo[Seq[CurrencyAccount]]
          complete(accounts)
        } ~ post {
          entity(as[NewAccount]) { newAccount =>
            val result = currencyAccountActor ? newAccount
            complete(result.mapTo[ActionPerformed])
          }
        }
      }
    }
  }
}
