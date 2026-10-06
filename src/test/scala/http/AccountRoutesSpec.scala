package http

import service.{AccountService, AccountTransferService}
import zio.*
import zio.http.*
import zio.schema.Schema
import zio.schema.codec.JsonCodec
import zio.test.*

object AccountRoutesSpec extends ZIOSpecDefault {
  import Schemas.given

  val testLayer: ULayer[AccountTransferService] =
    AccountService.layer >>> AccountTransferService.layer

  def run(request: Request): ZIO[AccountTransferService, Nothing, Response] =
    AccountRoutes.routes.runZIO(request)

  def decode[A](response: Response)(using schema: Schema[A]): Task[A] =
    response.body.asString.flatMap(body =>
      ZIO.fromEither(JsonCodec.jsonDecoder(schema).decodeJson(body)).mapError(new RuntimeException(_))
    )

  def postAccount(accountNumber: String, balance: String, currencyCode: String) =
    run(
      Request.post(
        "/api/accounts",
        Body.fromString(
          s"""{"accountNumber": "$accountNumber", "balance": $balance, "currencyCode": "$currencyCode"}"""
        )
      )
    )

  def transferRequest(from: String, to: String, amount: String) =
    Request.post(
      "/api/accounts/transfer",
      Body.fromString(s"""{"fromAccountNumber": "$from", "toAccountNumber": "$to", "amount": $amount}""")
    )

  def transferWithKey(key: String, from: String, to: String, amount: String) =
    run(transferRequest(from, to, amount).addHeader("Idempotency-Key", key))

  def transfer(from: String, to: String, amount: String) =
    Random.nextUUID.flatMap(uuid => transferWithKey(uuid.toString, from, to, amount))

  def balances = run(Request.get("/api/accounts"))
    .flatMap(decode[Seq[AccountResponse]])
    .map(_.map(account => account.accountNumber -> account.balance).toMap)

  def assertError(response: Response, status: Status, code: String) =
    for {
      error <- decode[ErrorResponse](response)
    } yield assertTrue(
      response.status == status,
      response.header(Header.ContentType).exists(_.mediaType == MediaType.application.json),
      error.error == code,
      error.message.nonEmpty
    )

  def badRequest(code: String)(response: Response)    = assertError(response, Status.BadRequest, code)
  def notFound(code: String)(response: Response)      = assertError(response, Status.NotFound, code)
  def conflict(code: String)(response: Response)      = assertError(response, Status.Conflict, code)
  def unprocessable(code: String)(response: Response) = assertError(response, Status.UnprocessableEntity, code)

  def spec = suite("AccountRoutesSpec")(
    test("creates an account and lists it") {
      for {
        created  <- postAccount("A", "50.00", "GBP")
        message  <- decode[SuccessResponse](created)
        accounts <- run(Request.get("/api/accounts")).flatMap(decode[Seq[AccountResponse]])
      } yield assertTrue(
        created.status == Status.Ok,
        message == SuccessResponse("Successfully added A into the datastore"),
        accounts == Seq(AccountResponse("A", BigDecimal("50.00"), "GBP"))
      )
    },
    test("accepts a balance with trailing zeros beyond the currency's minor units") {
      for {
        created <- postAccount("A", "50.000", "GBP")
      } yield assertTrue(created.status == Status.Ok)
    },
    test("rejects a negative opening balance") {
      postAccount("A", "-5.00", "GBP").flatMap(badRequest("CannotOpenAccountWithNegativeBalance"))
    },
    test("rejects an opening balance with more decimal places than the currency allows") {
      for {
        gbp <- postAccount("A", "5.123", "GBP").flatMap(badRequest("AmountHasTooManyDecimalPlaces"))
        jpy <- postAccount("B", "100.5", "JPY").flatMap(badRequest("AmountHasTooManyDecimalPlaces"))
      } yield gbp && jpy
    },
    test("rejects an unknown currency") {
      postAccount("A", "10", "ZZZ").flatMap(badRequest("UnknownCurrency"))
    },
    test("rejects a duplicate account") {
      for {
        _      <- postAccount("A", "10", "GBP")
        result <- postAccount("A", "10", "GBP").flatMap(conflict("AccountAlreadyExists"))
      } yield result
    },
    test("rejects a body that is not valid JSON for the endpoint with an error body") {
      for {
        response <- run(
                      Request
                        .post("/api/accounts/transfer", Body.fromString("""{"amount": "x"}"""))
                        .addHeader("Idempotency-Key", "bad-body")
                    )
        result <- assertError(response, Status.BadRequest, "MalformedBody")
      } yield result
    },
    test("rejects a form body, as curl -d sends, with an error body") {
      for {
        response <- run(
                      Request
                        .post("/api/accounts", Body.fromString("""{"accountNumber": "A"}"""))
                        .addHeader(Header.ContentType(MediaType.application.`x-www-form-urlencoded`))
                    )
        result <- assertError(response, Status.BadRequest, "MalformedBody")
      } yield result
    },
    test("transfers between accounts") {
      for {
        _        <- postAccount("A", "100", "GBP")
        _        <- postAccount("B", "0", "GBP")
        response <- transfer("A", "B", "58.60")
        message  <- decode[SuccessResponse](response)
        after    <- balances
      } yield assertTrue(
        response.status == Status.Ok,
        message == SuccessResponse("58.60 has been transferred from A to B"),
        after == Map("A" -> BigDecimal("41.40"), "B" -> BigDecimal("58.60"))
      )
    },
    test("rejects a transfer smaller than the currency's minor unit and leaves balances alone") {
      for {
        _      <- postAccount("A", "10", "GBP")
        _      <- postAccount("B", "0", "GBP")
        result <- transfer("A", "B", "0.0001").flatMap(badRequest("AmountHasTooManyDecimalPlaces"))
        after  <- balances
      } yield result && assertTrue(after == Map("A" -> BigDecimal(10), "B" -> BigDecimal(0)))
    },
    test("returns transfer errors as JSON with a status for each") {
      for {
        _              <- postAccount("A", "10", "GBP")
        _              <- postAccount("B", "10", "EUR")
        missing        <- transfer("X", "A", "1").flatMap(notFound("AccountDoesNotExist"))
        sameAccount    <- transfer("A", "A", "1").flatMap(badRequest("CannotTransferToSameAccount"))
        otherCurrency  <- transfer("A", "B", "1").flatMap(unprocessable("CannotTransferToAccountWithDifferentCurrency"))
        negativeAmount <- transfer("A", "B", "-1").flatMap(badRequest("CannotTransferNegativeAmount"))
      } yield missing && sameAccount && otherCurrency && negativeAmount
    },
    test("rejects a transfer the source account cannot cover") {
      for {
        _      <- postAccount("A", "10", "GBP")
        _      <- postAccount("B", "0", "GBP")
        result <- transfer("A", "B", "10.01").flatMap(unprocessable("AccountHasInsufficientFunds"))
      } yield result
    },
    test("applies a retried transfer once and answers the retry as the original") {
      for {
        _      <- postAccount("A", "100", "GBP")
        _      <- postAccount("B", "0", "GBP")
        first  <- transferWithKey("key-1", "A", "B", "10")
        second <- transferWithKey("key-1", "A", "B", "10")
        body1  <- first.body.asString
        body2  <- second.body.asString
        after  <- balances
      } yield assertTrue(
        first.status == Status.Ok,
        second.status == Status.Ok,
        body1 == body2,
        after == Map("A" -> BigDecimal(90), "B" -> BigDecimal(10))
      )
    },
    test("refuses an idempotency key reused for a different transfer") {
      for {
        _      <- postAccount("A", "100", "GBP")
        _      <- postAccount("B", "0", "GBP")
        _      <- transferWithKey("key-1", "A", "B", "10")
        result <- transferWithKey("key-1", "A", "B", "20").flatMap(conflict("IdempotencyKeyReusedForDifferentTransfer"))
        after  <- balances
      } yield result && assertTrue(after == Map("A" -> BigDecimal(90), "B" -> BigDecimal(10)))
    },
    test("refuses a transfer without an idempotency key") {
      for {
        _       <- postAccount("A", "100", "GBP")
        _       <- postAccount("B", "0", "GBP")
        missing <- run(transferRequest("A", "B", "10")).flatMap(badRequest("MissingHeader"))
        blank   <- transferWithKey(" ", "A", "B", "10").flatMap(badRequest("IdempotencyKeyIsBlank"))
        after   <- balances
      } yield missing && blank && assertTrue(after == Map("A" -> BigDecimal(100), "B" -> BigDecimal(0)))
    },
    test("describes the error body in the OpenAPI spec") {
      val spec = AccountRoutes.openAPISpec.toJson
      assertTrue(
        spec.contains("ErrorResponse"),
        spec.contains("TransferRequest"),
        spec.contains("Idempotency-Key"),
        spec.contains("\"404\""),
        spec.contains("\"409\""),
        spec.contains("\"422\"")
      )
    }
  ).provide(testLayer, Runtime.removeDefaultLoggers >>> ZTestLogger.default)
}
