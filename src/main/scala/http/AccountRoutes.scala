package http

import domain.*
import service.*
import zio.*
import zio.http.*
import zio.http.codec.HeaderCodec
import zio.http.endpoint.*
import zio.http.endpoint.openapi.*
import zio.schema.{DeriveSchema, Schema}

import java.util.Currency

case class AccountResponse(accountNumber: String, balance: BigDecimal, currencyCode: String)
case class PostNewAccountRequest(accountNumber: String, balance: BigDecimal, currencyCode: String)
case class TransferRequest(fromAccountNumber: String, toAccountNumber: String, amount: BigDecimal)
case class ErrorResponse(error: String, message: String)
case class SuccessResponse(message: String)

object Schemas:
  given accountResponseSchema: Schema[AccountResponse]         = DeriveSchema.gen[AccountResponse]
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

  private val postAccountEndpoint =
    Endpoint(RoutePattern.POST / "api" / "accounts")
      .in[PostNewAccountRequest]
      .out[SuccessResponse]
      .outErrors[ApiError](ApiError.badRequest, ApiError.conflict)
      .copy(codecError = ApiError.requestCodecError)

  private val transferEndpoint =
    Endpoint(RoutePattern.POST / "api" / "accounts" / "transfer")
      .header(HeaderCodec.headerAs[String]("Idempotency-Key"))
      .in[TransferRequest]
      .out[SuccessResponse]
      .outErrors[ApiError](ApiError.badRequest, ApiError.notFound, ApiError.conflict, ApiError.unprocessableEntity)
      .copy(codecError = ApiError.requestCodecError)

  val openAPISpec: OpenAPI =
    OpenAPIGen.fromEndpoints(
      title = "Simple Money API",
      version = "1.0.0",
      getAccountsEndpoint,
      postAccountEndpoint,
      transferEndpoint
    )

  private val getAccounts = getAccountsEndpoint.implement: _ =>
    AccountTransferService.listAllAccounts.map(_.map(toAccountResponse))

  private val postAccount = postAccountEndpoint.implement: request =>
    (for
      currency <- ZIO
                    .attempt(Currency.getInstance(request.currencyCode))
                    .orElseFail(TransferServiceErrors.UnknownCurrency)
      _ <- AccountTransferService.addNewAccount(
             CurrencyAccount(AccountNumber(request.accountNumber), CurrencyAmount(request.balance), currency)
           )
    yield SuccessResponse(s"Successfully added ${request.accountNumber} into the datastore"))
      .mapError(ApiError.from)

  private val transfer = transferEndpoint.implement: (idempotencyKey, request) =>
    (ZIO.fail(TransferServiceErrors.IdempotencyKeyIsBlank).when(idempotencyKey.isBlank) *>
      AccountTransferService.accountTransfer(
        IdempotencyKey(idempotencyKey),
        AccountNumber(request.fromAccountNumber),
        AccountNumber(request.toAccountNumber),
        CurrencyAmount(request.amount)
      ))
      .as(
        SuccessResponse(
          s"${request.amount} has been transferred from ${request.fromAccountNumber} to ${request.toAccountNumber}"
        )
      )
      .mapError(ApiError.from)

  val routes: Routes[AccountTransferService, Response] =
    Routes(getAccounts, postAccount, transfer)
