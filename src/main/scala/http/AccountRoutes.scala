package http

import domain.*
import service.*
import zio.*
import zio.http.*
import zio.http.codec.{HeaderCodec, HttpCodec, PathCodec}
import zio.http.endpoint.*
import zio.http.endpoint.openapi.*
import zio.schema.{DeriveSchema, Schema}

import java.util.{Currency, UUID}

case class AccountResponse(id: String, accountNumber: String, balance: BigDecimal, currencyCode: String)
case class LookupRequest(accountNumber: String)
case class AccountPageResponse(accounts: List[AccountResponse], next: Option[String])
case class PostNewAccountRequest(accountNumber: String, balance: BigDecimal, currencyCode: String)
case class TransferRequest(fromAccountNumber: String, toAccountNumber: String, amount: BigDecimal)
case class ErrorResponse(error: String, message: String)
case class SuccessResponse(message: String)

object Schemas:
  given accountResponseSchema: Schema[AccountResponse]          = DeriveSchema.gen[AccountResponse]
  given accountPageResponseSchema: Schema[AccountPageResponse]  = DeriveSchema.gen[AccountPageResponse]
  given postAccountRequestSchema: Schema[PostNewAccountRequest] = DeriveSchema.gen[PostNewAccountRequest]
  given lookupRequestSchema: Schema[LookupRequest]              = DeriveSchema.gen[LookupRequest]
  given transferRequestSchema: Schema[TransferRequest]          = DeriveSchema.gen[TransferRequest]
  given errorResponseSchema: Schema[ErrorResponse]              = DeriveSchema.gen[ErrorResponse]
  given successResponseSchema: Schema[SuccessResponse]          = DeriveSchema.gen[SuccessResponse]

  def toAccountResponse(account: CurrencyAccount): AccountResponse =
    AccountResponse(
      account.id.value.toString,
      account.accountNumber.value,
      account.balance.amount,
      account.currency.getCurrencyCode
    )

  /**
   * JSON such as 1e3 reads as a BigDecimal with a negative scale, which would
   * be written back as 1E+3, so amounts are given a scale of at least zero.
   * Amounts over the maximum are left alone, to be refused, since rescaling one
   * such as 1e999999999 overflows.
   */
  def plain(amount: BigDecimal): BigDecimal =
    if amount.scale < 0 && Money.isWithinMaximum(amount) then amount.setScale(0) else amount

  def toAccountPageResponse(page: AccountPage): AccountPageResponse =
    AccountPageResponse(page.accounts.map(toAccountResponse).toList, page.next.map(_.value.toString))

  def location(account: CurrencyAccount): Header.Location =
    Header.Location(URL(Path.root / "api" / "accounts" / account.id.value.toString))

object AccountRoutes:
  import Schemas.{*, given}

  private val defaultPageSize = 100

  private val getAccountsEndpoint =
    Endpoint(RoutePattern.GET / "api" / "accounts")
      .query(HttpCodec.query[Int]("limit").optional)
      .query(HttpCodec.query[UUID]("after").optional)
      .out[AccountPageResponse]
      .outError[ApiError.BadRequest](Status.BadRequest)
      .copy(codecError = ApiError.requestCodecError)

  private val getAccountEndpoint =
    Endpoint(RoutePattern.GET / "api" / "accounts" / PathCodec.uuid("id"))
      .out[AccountResponse]
      .outError[ApiError.NotFound](Status.NotFound)

  // A POST, so the account number travels in the body rather than the URL
  private val lookupEndpoint =
    Endpoint(RoutePattern.POST / "api" / "accounts" / "lookup")
      .in[LookupRequest]
      .out[AccountResponse]
      .outErrors[ApiError.BadRequest | ApiError.NotFound](ApiError.badRequest, ApiError.notFound)
      .copy(codecError = ApiError.requestCodecError)

  private val postAccountEndpoint =
    Endpoint(RoutePattern.POST / "api" / "accounts")
      .in[PostNewAccountRequest]
      .out[AccountResponse](Status.Created)
      .outHeader(HeaderCodec.location)
      .outErrors[ApiError.BadRequest | ApiError.Conflict](ApiError.badRequest, ApiError.conflict)
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
      getAccountEndpoint,
      lookupEndpoint,
      postAccountEndpoint,
      transferEndpoint
    )

  private val getAccounts = getAccountsEndpoint.implement: (limit, after) =>
    AccountService
      .listAccounts(after.map(AccountId(_)), limit.getOrElse(defaultPageSize))
      .map(toAccountPageResponse)
      .mapError(ApiError.invalid)

  private val getAccount = getAccountEndpoint.implement: id =>
    AccountService
      .getAccount(AccountId(id))
      .map(toAccountResponse)
      .mapError(ApiError.missing)

  private val lookup = lookupEndpoint.implement: request =>
    AccountService
      .findAccount(AccountNumber(request.accountNumber))
      .map(toAccountResponse)
      .mapError(ApiError.missing)

  private val postAccount = postAccountEndpoint.implement: request =>
    (for
      currency <- ZIO
                    .attempt(Currency.getInstance(request.currencyCode))
                    .orElseFail(AccountError.UnknownCurrency)
      account <-
        AccountService.openAccount(AccountNumber(request.accountNumber), Money(plain(request.balance), currency))
    yield (toAccountResponse(account), location(account)))
      .mapError:
        case error: AccountError.Invalid     => ApiError.invalid(error)
        case error: AccountError.Conflicting => ApiError.conflicting(error)

  private val transfer = transferEndpoint.implement: (idempotencyKey, request) =>
    (ZIO.fail(AccountError.IdempotencyKeyIsBlank).when(idempotencyKey.isBlank) *>
      AccountService.accountTransfer(
        IdempotencyKey(idempotencyKey),
        AccountNumber(request.fromAccountNumber),
        AccountNumber(request.toAccountNumber),
        plain(request.amount)
      ))
      .as(
        SuccessResponse(
          s"${plain(request.amount)} has been transferred from ${request.fromAccountNumber} to ${request.toAccountNumber}"
        )
      )
      .mapError(ApiError.from)

  val routes: Routes[AccountService, Response] =
    Routes(getAccounts, getAccount, lookup, postAccount, transfer)
