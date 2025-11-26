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
import sttp.model.StatusCode
import sttp.tapir._
import sttp.tapir.stringToPath
import sttp.tapir.generic.auto._
import sttp.tapir.json.circe._
import sttp.tapir.server.pekkohttp.PekkoHttpServerInterpreter
import sttp.tapir.swagger.bundle.SwaggerInterpreter

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._
import scala.concurrent.Future
import scala.language.implicitConversions

trait AccountRoutes extends CirceSupport with JsonCodecs {

  implicit def system: ActorSystem

  implicit def executionContext: ExecutionContext = system.dispatcher

  lazy val log = Logging(system, classOf[AccountRoutes])

  def currencyAccountActor: ActorRef

  implicit val timeout: Timeout = Timeout(5.seconds)

  private val listAccountsEndpoint: PublicEndpoint[Unit, Unit, Seq[CurrencyAccount], Any] =
    endpoint.get
      .in("api" / "accounts")
      .out(jsonBody[Seq[CurrencyAccount]])
      .summary("List all accounts")
      .description("Returns the complete list of currency accounts.")

  private val createAccountEndpoint
      : PublicEndpoint[PostNewAccount, String, String, Any] =
    endpoint.post
      .in("api" / "accounts")
      .in(jsonBody[PostNewAccount])
      .out(stringBody.description("Account created."))
      .errorOut(
        oneOf[String](statusMapping(StatusCode.BadRequest, stringBody.description("Account already exists.")))
      )
      .summary("Create a new account")
      .description("Creates a new account with the provided initial balance and currency.")

  private val transferEndpoint
      : PublicEndpoint[TransferBetweenAccounts, String, String, Any] =
    endpoint.put
      .in("api" / "accounts" / "transfer")
      .in(jsonBody[TransferBetweenAccounts])
      .out(stringBody.description("Transfer processed."))
      .errorOut(
        oneOf[String](statusMapping(StatusCode.BadRequest, stringBody.description("Transfer failed.")))
      )
      .summary("Transfer funds between accounts")
      .description("Transfers funds from one account to another, returning any validation errors.")

  private val swaggerEndpoints = SwaggerInterpreter()
    .fromEndpoints[Future](
      List(listAccountsEndpoint, createAccountEndpoint, transferEndpoint),
      "Simple Money API",
      "1.0.0"
    )

  private val swaggerRoutes: Route = PekkoHttpServerInterpreter().toRoute(swaggerEndpoints)

  lazy val accountRoutes: Route = {
    swaggerRoutes ~ pathPrefix("api" / "accounts" / "transfer") {
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
