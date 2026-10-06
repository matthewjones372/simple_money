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
    ZIO.scoped(AccountRoutes.routes.runZIO(request))

  // Request.get(String) takes the string as a path, so a query string or percent-encoding would not be parsed
  def get(url: String) = run(Request.get(URL.decode(url).toOption.get))

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

  def balances = get("/api/accounts")
    .flatMap(decode[AccountPageResponse])
    .map(_.accounts.map(account => account.accountNumber -> account.balance).toMap)

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
        accounts <- get("/api/accounts").flatMap(decode[AccountPageResponse])
      } yield assertTrue(
        created.status == Status.Ok,
        message == SuccessResponse("Successfully added A into the datastore"),
        accounts == AccountPageResponse(List(AccountResponse("A", BigDecimal("50.00"), "GBP")), None)
      )
    },
    test("gets one account by its number, including one with spaces") {
      for {
        _        <- postAccount("GB29 NWBK 6016 1331 3282 19", "50.00", "GBP")
        response <- get("/api/accounts/GB29%20NWBK%206016%201331%203282%2019")
        account  <- decode[AccountResponse](response)
      } yield assertTrue(
        response.status == Status.Ok,
        account == AccountResponse("GB29 NWBK 6016 1331 3282 19", BigDecimal("50.00"), "GBP")
      )
    },
    test("returns 404 for an account that does not exist") {
      get("/api/accounts/NOPE").flatMap(notFound("AccountDoesNotExist"))
    },
    test("pages through accounts with limit and after") {
      for {
        _      <- ZIO.foreachDiscard(List("C", "A", "E", "B", "D"))(postAccount(_, "1", "GBP"))
        first  <- get("/api/accounts?limit=2").flatMap(decode[AccountPageResponse])
        second <-
          get(s"/api/accounts?limit=2&after=${first.next.get}").flatMap(decode[AccountPageResponse])
        third <-
          get(s"/api/accounts?limit=2&after=${second.next.get}").flatMap(decode[AccountPageResponse])
      } yield assertTrue(
        first.accounts.map(_.accountNumber) == List("A", "B"),
        first.next.contains("B"),
        second.accounts.map(_.accountNumber) == List("C", "D"),
        third.accounts.map(_.accountNumber) == List("E"),
        third.next.isEmpty
      )
    },
    test("returns at most 100 accounts when no limit is given") {
      for {
        _    <- ZIO.foreachDiscard(1 to 101)(n => postAccount(f"ACC$n%03d", "1", "GBP"))
        page <- get("/api/accounts").flatMap(decode[AccountPageResponse])
      } yield assertTrue(page.accounts.size == 100, page.next.contains("ACC100"))
    },
    test("refuses a limit outside 1 to 1000, or one that is not a number") {
      for {
        zero    <- get("/api/accounts?limit=0").flatMap(badRequest("InvalidPageSize"))
        tooMany <- get("/api/accounts?limit=1001").flatMap(badRequest("InvalidPageSize"))
        word    <- get("/api/accounts?limit=ten").flatMap(badRequest("MalformedQueryParam"))
      } yield zero && tooMany && word
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
        spec.toLowerCase.contains("idempotency-key"),
        spec.contains("\"404\""),
        spec.contains("\"409\""),
        spec.contains("\"422\"")
      )
    }
  ).provide(testLayer, Runtime.removeDefaultLoggers >>> ZTestLogger.default)
}
