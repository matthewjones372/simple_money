package infrastructure.actors

import org.apache.pekko.actor.{ActorRef, ActorSystem}
import org.apache.pekko.event.Logging
import org.apache.pekko.http.scaladsl.model.{HttpResponse, StatusCodes}
import org.apache.pekko.http.scaladsl.unmarshalling.Unmarshal
import org.apache.pekko.http.scaladsl.server.Route
import org.apache.pekko.http.scaladsl.server.RouteConcatenation
import org.apache.pekko.pattern.ask
import org.apache.pekko.util.Timeout
import domain.model.CurrencyAccount
import infrastructure.actors.AccountActor._
import infrastructure.actors.CirceSupport
import sttp.model.StatusCode
import sttp.apispec.openapi.Server
import sttp.tapir._
import sttp.tapir.stringToPath
import sttp.tapir.generic.auto._
import sttp.tapir.json.circe._
import sttp.tapir.{Codec, CodecFormat}
import sttp.tapir.server.pekkohttp.PekkoHttpServerInterpreter
import sttp.tapir.swagger.bundle.SwaggerInterpreter

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._
import scala.concurrent.Future
import scala.language.implicitConversions

trait AccountRoutes extends CirceSupport with JsonCodecs {

  import RouteConcatenation._

  implicit def system: ActorSystem

  implicit def executionContext: ExecutionContext = system.dispatcher

  lazy val log = Logging(system, classOf[AccountRoutes])

  def currencyAccountActor: ActorRef

  implicit val timeout: Timeout = Timeout(5.seconds)

  implicit val stringJsonCodec: Codec[String, String, CodecFormat] =
    Codec.string.format(CodecFormat.Json())

  private def toResult(response: HttpResponse): Future[Either[String, String]] = {
    implicit val actorSystem: ActorSystem = system
    Unmarshal(response.entity).to[String].map { body =>
      if (response.status == StatusCodes.BadRequest) Left(body) else Right(body)
    }
  }

  private val listAccountsEndpoint: PublicEndpoint[Unit, Unit, Seq[CurrencyAccount], Any] =
    endpoint.get
      .in("api")
      .in("accounts")
      .out(jsonBody[Seq[CurrencyAccount]])
      .summary("List all accounts")
      .description("Returns the complete list of currency accounts.")

  private val createAccountEndpoint
      : PublicEndpoint[PostNewAccount, String, String, Any] =
    endpoint.post
      .in("api")
      .in("accounts")
      .in(jsonBody[PostNewAccount])
      .out(stringBodyUtf8AnyFormat(stringJsonCodec).description("Account created."))
      .errorOut(
        statusCode(StatusCode.BadRequest)
          .and(stringBodyUtf8AnyFormat(stringJsonCodec).description("Account already exists."))
      )
      .summary("Create a new account")
      .description("Creates a new account with the provided initial balance and currency.")

  private val transferEndpoint
      : PublicEndpoint[TransferBetweenAccounts, String, String, Any] =
    endpoint.put
      .in("api")
      .in("accounts")
      .in("transfer")
      .in(jsonBody[TransferBetweenAccounts])
      .out(stringBodyUtf8AnyFormat(stringJsonCodec).description("Transfer processed."))
      .errorOut(
        statusCode(StatusCode.BadRequest)
          .and(stringBodyUtf8AnyFormat(stringJsonCodec).description("Transfer failed."))
      )
      .summary("Transfer funds between accounts")
      .description("Transfers funds from one account to another, returning any validation errors.")

  private lazy val listAccountsServerEndpoint = listAccountsEndpoint.serverLogicSuccess[Future] { _ =>
    (currencyAccountActor ? GetAccounts).mapTo[Seq[CurrencyAccount]]
  }

  private lazy val createAccountServerEndpoint = createAccountEndpoint.serverLogic[Future] { newAccount =>
    (currencyAccountActor ? newAccount)
      .mapTo[HttpResponse]
      .flatMap(toResult)
  }

  private lazy val transferServerEndpoint = transferEndpoint.serverLogic[Future] { transfer =>
    (currencyAccountActor ? transfer)
      .mapTo[HttpResponse]
      .flatMap(toResult)
  }

  private lazy val swaggerEndpoints = SwaggerInterpreter(
    customiseDocsModel = _.servers(List(Server("http://localhost:8081")))
  ).fromEndpoints[Future](
    List(listAccountsEndpoint, createAccountEndpoint, transferEndpoint),
    "Simple Money API",
    "1.0.0"
  )

  private lazy val apiRoutes: Route = PekkoHttpServerInterpreter().toRoute(
    List(
      listAccountsServerEndpoint,
      createAccountServerEndpoint,
      transferServerEndpoint
    )
  )

  private lazy val swaggerRoutes: Route = PekkoHttpServerInterpreter().toRoute(swaggerEndpoints)

  lazy val accountRoutes: Route = {
    swaggerRoutes ~ apiRoutes
  }
}
