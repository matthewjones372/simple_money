package http

import service.TransferServiceErrors
import service.TransferServiceErrors.*
import zio.http.Status
import zio.http.codec.{HttpCodec, HttpCodecError, HttpCodecType}
import zio.schema.Schema

/**
 * An error response, sent with the status of its case. Every case has the same
 * `ErrorResponse` body, whose `error` is a stable code for clients to match on
 * and whose `message` is for people.
 */
sealed trait ApiError:
  def body: ErrorResponse

object ApiError:
  final case class BadRequest(body: ErrorResponse)          extends ApiError
  final case class NotFound(body: ErrorResponse)            extends ApiError
  final case class Conflict(body: ErrorResponse)            extends ApiError
  final case class UnprocessableEntity(body: ErrorResponse) extends ApiError

  import Schemas.errorResponseSchema

  given Schema[BadRequest]          = errorResponseSchema.transform(BadRequest(_), _.body)
  given Schema[NotFound]            = errorResponseSchema.transform(NotFound(_), _.body)
  given Schema[Conflict]            = errorResponseSchema.transform(Conflict(_), _.body)
  given Schema[UnprocessableEntity] = errorResponseSchema.transform(UnprocessableEntity(_), _.body)

  val badRequest: HttpCodec[HttpCodecType.Status & HttpCodecType.Content, BadRequest] =
    HttpCodec.error[BadRequest](Status.BadRequest)
  val notFound: HttpCodec[HttpCodecType.Status & HttpCodecType.Content, NotFound] =
    HttpCodec.error[NotFound](Status.NotFound)
  val conflict: HttpCodec[HttpCodecType.Status & HttpCodecType.Content, Conflict] =
    HttpCodec.error[Conflict](Status.Conflict)
  val unprocessableEntity: HttpCodec[HttpCodecType.Status & HttpCodecType.Content, UnprocessableEntity] =
    HttpCodec.error[UnprocessableEntity](Status.UnprocessableEntity)

  def from(error: TransferServiceErrors): ApiError =
    error match
      case UnknownCurrency =>
        BadRequest(ErrorResponse("UnknownCurrency", "The currency code is not an ISO 4217 code"))
      case CannotOpenAccountWithNegativeBalance =>
        BadRequest(
          ErrorResponse("CannotOpenAccountWithNegativeBalance", "An account cannot open with a negative balance")
        )
      case AmountHasTooManyDecimalPlaces =>
        BadRequest(
          ErrorResponse("AmountHasTooManyDecimalPlaces", "The amount has more decimal places than its currency allows")
        )
      case CannotTransferNegativeAmount =>
        BadRequest(ErrorResponse("CannotTransferNegativeAmount", "The amount to transfer must be more than zero"))
      case CannotTransferToSameAccount =>
        BadRequest(ErrorResponse("CannotTransferToSameAccount", "An account cannot transfer to itself"))
      case IdempotencyKeyIsBlank =>
        BadRequest(ErrorResponse("IdempotencyKeyIsBlank", "The Idempotency-Key header cannot be blank"))
      case InvalidPageSize =>
        BadRequest(ErrorResponse("InvalidPageSize", "The limit must be between 1 and 1000"))
      case AccountDoesNotExist =>
        NotFound(ErrorResponse("AccountDoesNotExist", "An account in the request does not exist"))
      case AccountAlreadyExists =>
        Conflict(ErrorResponse("AccountAlreadyExists", "An account with this number already exists"))
      case IdempotencyKeyReusedForDifferentTransfer =>
        Conflict(
          ErrorResponse(
            "IdempotencyKeyReusedForDifferentTransfer",
            "This Idempotency-Key was already used for a different transfer"
          )
        )
      case AccountHasInsufficientFunds =>
        UnprocessableEntity(
          ErrorResponse("AccountHasInsufficientFunds", "The account does not have enough money for this transfer")
        )
      case CannotTransferToAccountWithDifferentCurrency =>
        UnprocessableEntity(
          ErrorResponse(
            "CannotTransferToAccountWithDifferentCurrency",
            "Both accounts in a transfer must have the same currency"
          )
        )

  /**
   * Replaces ZIO HTTP's own response for a request it cannot decode, which is
   * HTML by default and names its internal types, with a `400` `ErrorResponse`.
   */
  val requestCodecError: HttpCodec[HttpCodecType.ResponseType, HttpCodecError] =
    HttpCodec
      .error[BadRequest](Status.BadRequest)
      .transformOrFail[HttpCodecError](badRequest => Left(s"Not a codec error: ${badRequest.body}")): codecError =>
        Right(BadRequest(fromCodecError(codecError)))

  private def fromCodecError(error: HttpCodecError): ErrorResponse =
    error match
      case HttpCodecError.MissingHeader(name) =>
        ErrorResponse("MissingHeader", s"The ${headerName(name)} header is required")
      case HttpCodecError.MissingHeaders(names) =>
        ErrorResponse("MissingHeader", s"The ${names.map(headerName).mkString(", ")} header is required")
      case HttpCodecError.MalformedHeader(name, _) =>
        ErrorResponse("MalformedHeader", s"The ${headerName(name)} header could not be read")
      case HttpCodecError.DecodingErrorHeader(name, _) =>
        ErrorResponse("MalformedHeader", s"The ${headerName(name)} header could not be read")
      case HttpCodecError.MalformedQueryParam(name, _) =>
        ErrorResponse("MalformedQueryParam", s"The $name query parameter could not be read")
      case _: HttpCodecError.UnsupportedContentType =>
        ErrorResponse("UnsupportedContentType", "The request body must be JSON")
      case _: HttpCodecError.MalformedBody =>
        ErrorResponse("MalformedBody", "The request body is not valid JSON for this endpoint")
      case _ =>
        ErrorResponse("InvalidRequest", "The request could not be read")

  // ZIO HTTP reports a header declared as Idempotency-Key as idempotency-Key
  private def headerName(name: String): String =
    name.split('-').map(_.capitalize).mkString("-")
