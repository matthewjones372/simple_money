package infrastructure.actors

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.event.Logging
import org.apache.pekko.http.scaladsl.model.StatusCodes
import org.apache.pekko.http.scaladsl.server.Route
import org.apache.pekko.http.scaladsl.server.RouteConcatenation
import org.apache.pekko.util.Timeout
import cats.Eval
import infrastructure.actors.AccountActor.{PostNewAccount, TransferBetweenAccounts}
import domain.model.{AccountNumber, CurrencyAccount, CurrencyAmount}
import infrastructure.actors.CirceSupport
import sttp.model.StatusCode
import sttp.apispec.openapi.{OpenAPI, Server}
import sttp.tapir._
import sttp.tapir.stringToPath
import sttp.tapir.generic.auto._
import sttp.tapir.json.circe._
import sttp.tapir.{Codec, CodecFormat}
import sttp.tapir.server.pekkohttp.PekkoHttpServerInterpreter
import sttp.tapir.swagger.bundle.SwaggerInterpreter

import scala.concurrent.ExecutionContext
import service.AccountTransferService

import scala.concurrent.Future
import scala.concurrent.duration._
import scala.language.implicitConversions

trait AccountRoutes extends CirceSupport with JsonCodecs {

  import RouteConcatenation._

  implicit def system: ActorSystem

  implicit def executionContext: ExecutionContext = system.dispatcher

  lazy val log = Logging(system, classOf[AccountRoutes])

  def transferService: AccountTransferService[Eval]

  implicit val timeout: Timeout = Timeout(5.seconds)

  implicit val stringJsonCodec: Codec[String, String, CodecFormat] =
    Codec.string.format(CodecFormat.Json())

  def swaggerServerUrl: Option[String] =
    sys.env.get("SWAGGER_SERVER_URL").orElse(sys.props.get("swagger.server.url")).orElse(Some("http://localhost:8081"))

  private def createAccount(
    newAccount: PostNewAccount
  ): Future[Either[String, String]] = Future {
    transferService
      .addNewAccount(
        CurrencyAccount(
          AccountNumber.fromString(newAccount.accountNumber),
          CurrencyAmount.fromBigDecimal(newAccount.balance),
          newAccount.currency
        )
      )
      .value
      .left
      .map(err => s"Could not add ${newAccount.accountNumber} into the data store reason: $err")
      .map(_ => s"Successfully added ${newAccount.accountNumber} into the datastore")
  }

  private def transferBetween(
    transfer: TransferBetweenAccounts
  ): Future[Either[String, String]] = Future {
    transferService
      .accountTransfer(
        AccountNumber.fromString(transfer.fromAccountNumber),
        AccountNumber.fromString(transfer.toAccountNumber),
        CurrencyAmount.fromBigDecimal(transfer.amount)
      )
      .map(_.left.map(_.toString))
      .value
      .left
      .map(err => s"Transfer unsuccessful from account ${transfer.fromAccountNumber} to ${transfer.toAccountNumber} error: $err")
      .map(_ => s"${transfer.amount} has been transferred from ${transfer.fromAccountNumber} to ${transfer.toAccountNumber}")
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
    log.info("Handling list accounts request")
    Future.successful(transferService.listAllAccounts.value)
  }

  private lazy val createAccountServerEndpoint = createAccountEndpoint.serverLogic[Future] { newAccount =>
    log.info(s"Handling create account request for ${newAccount.accountNumber}")
    createAccount(newAccount)
  }

  private lazy val transferServerEndpoint = transferEndpoint.serverLogic[Future] { transfer =>
    log.info(s"Handling transfer request from ${transfer.fromAccountNumber} to ${transfer.toAccountNumber}")
    transferBetween(transfer)
  }

  private lazy val swaggerEndpoints = {
    val customiseDocsModel: OpenAPI => OpenAPI = swaggerServerUrl
      .map(url => (openApi: OpenAPI) => openApi.servers(List(Server(url))))
      .getOrElse(identity[OpenAPI])

    SwaggerInterpreter(customiseDocsModel = customiseDocsModel).fromEndpoints[Future](
      List(listAccountsEndpoint, createAccountEndpoint, transferEndpoint),
      "Simple Money API",
      "1.0.0"
    )
  }

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
