package http

import domain.*
import service.*
import zio.*
import zio.http.*
import zio.http.endpoint.*
import zio.http.endpoint.openapi.*
import zio.schema.{DeriveSchema, Schema}
import zio.schema.codec.JsonCodec

import java.util.Currency

case class AccountResponse(accountNumber: String, balance: BigDecimal, currencyCode: String)
case class PostNewAccountRequest(accountNumber: String, balance: BigDecimal, currencyCode: String)
case class TransferRequest(fromAccountNumber: String, toAccountNumber: String, amount: BigDecimal)
case class ErrorResponse(error: String)
case class SuccessResponse(message: String)

object Schemas:
  given accountResponseSchema: Schema[AccountResponse] = DeriveSchema.gen[AccountResponse]
  given accountResponseSeqSchema: Schema[Seq[AccountResponse]] =
    Schema.list[AccountResponse].transform(_.toSeq, _.toList)
  given postAccountRequestSchema: Schema[PostNewAccountRequest] = DeriveSchema.gen[PostNewAccountRequest]
  given transferRequestSchema: Schema[TransferRequest]          = DeriveSchema.gen[TransferRequest]
  given errorResponseSchema: Schema[ErrorResponse]              = DeriveSchema.gen[ErrorResponse]
  given successResponseSchema: Schema[SuccessResponse]          = DeriveSchema.gen[SuccessResponse]

  def toAccountResponse(account: CurrencyAccount): AccountResponse =
    AccountResponse(
      account.accountNumber.value,
      account.balance.value,
      account.currency.getCurrencyCode
    )

object AccountRoutes:
  import Schemas.{*, given}

  private val getAccountsEndpoint =
    Endpoint(RoutePattern.GET / "api" / "accounts")
      .out[Seq[AccountResponse]]
      .outError[ErrorResponse](Status.InternalServerError)

  private val postAccountEndpoint =
    Endpoint(RoutePattern.POST / "api" / "accounts")
      .in[PostNewAccountRequest]
      .out[SuccessResponse]
      .outError[ErrorResponse](Status.BadRequest)

  private val transferEndpoint =
    Endpoint(RoutePattern.PUT / "api" / "accounts" / "transfer")
      .in[TransferRequest]
      .out[SuccessResponse]
      .outError[ErrorResponse](Status.BadRequest)

  val openAPISpec: OpenAPI =
    val baseSpec = OpenAPIGen.fromEndpoints(
      title = "Simple Money API",
      version = "1.0.0",
      getAccountsEndpoint,
      postAccountEndpoint,
      transferEndpoint
    )

    import scala.collection.immutable.ListMap

    val schemasMap = ListMap(
      OpenAPI.Key.fromString("AccountResponse").get -> OpenAPI.ReferenceOr.Or(
        JsonSchema.fromZSchema(accountResponseSchema)
      ),
      OpenAPI.Key.fromString("ErrorResponse").get -> OpenAPI.ReferenceOr.Or(
        JsonSchema.fromZSchema(errorResponseSchema)
      ),
      OpenAPI.Key.fromString("SuccessResponse").get -> OpenAPI.ReferenceOr.Or(
        JsonSchema.fromZSchema(successResponseSchema)
      ),
      OpenAPI.Key.fromString("PostNewAccountRequest").get -> OpenAPI.ReferenceOr.Or(
        JsonSchema.fromZSchema(postAccountRequestSchema)
      ),
      OpenAPI.Key.fromString("TransferRequest").get -> OpenAPI.ReferenceOr.Or(
        JsonSchema.fromZSchema(transferRequestSchema)
      )
    )

    val newComponents = baseSpec.components
      .map(_.copy(schemas = schemasMap))
      .getOrElse(OpenAPI.Components(schemas = schemasMap))

    baseSpec.copy(components = Some(newComponents))

  val routes: Routes[AccountService & AccountTransferService, Response] = Routes(
    Method.GET / "api" / "accounts" -> handler:
      AccountTransferService.listAllAccounts.map: accounts =>
        val responses = accounts.map(toAccountResponse)
        val json      = JsonCodec.jsonEncoder(accountResponseSeqSchema).encodeJson(responses, None)
        Response.json(json.toString)
    ,

    Method.POST / "api" / "accounts" -> handler: (req: Request) =>
      val result = for
        body     <- req.body.asString
        decoded  <- ZIO.fromEither(JsonCodec.jsonDecoder(postAccountRequestSchema).decodeJson(body))
        currency <- ZIO.attempt(Currency.getInstance(decoded.currencyCode))
        account = CurrencyAccount(
                    AccountNumber(decoded.accountNumber),
                    CurrencyAmount(decoded.balance),
                    currency
                  )
        _       <- AccountTransferService.addNewAccount(account)
        response = SuccessResponse(s"Successfully added ${decoded.accountNumber} into the datastore")
        json     = JsonCodec.jsonEncoder(successResponseSchema).encodeJson(response, None)
      yield Response.json(json.toString)

      result.catchAll:
        case err: TransferServiceErrors => ZIO.succeed(Response.badRequest(err.toString))
        case err: Throwable             => ZIO.succeed(Response.badRequest(err.getMessage))
        case err                        => ZIO.succeed(Response.badRequest(err.toString))
    ,

    Method.PUT / "api" / "accounts" / "transfer" -> handler: (req: Request) =>
      val result = for
        body    <- req.body.asString
        decoded <- ZIO.fromEither(JsonCodec.jsonDecoder(transferRequestSchema).decodeJson(body))
        _ <- AccountTransferService.accountTransfer(
               AccountNumber(decoded.fromAccountNumber),
               AccountNumber(decoded.toAccountNumber),
               CurrencyAmount(decoded.amount)
             )
        response =
          SuccessResponse(
            s"${decoded.amount} has been transferred from ${decoded.fromAccountNumber} to ${decoded.toAccountNumber}"
          )
        json = JsonCodec.jsonEncoder(successResponseSchema).encodeJson(response, None)
      yield Response.json(json.toString)

      result.catchAll:
        case err: TransferServiceErrors => ZIO.succeed(Response.badRequest(err.toString))
        case err: Throwable             => ZIO.succeed(Response.badRequest(err.getMessage))
        case err                        => ZIO.succeed(Response.badRequest(err.toString))
  )
