package http

import service.{AccountService, AccountTransferService}
import zio.*
import zio.http.*
import zio.schema.Schema
import zio.schema.codec.JsonCodec
import zio.test.*

object AccountRoutesSpec extends ZIOSpecDefault {
  import Schemas.given

  val testLayer: ULayer[AccountService & AccountTransferService] =
    AccountService.layer ++ AccountTransferService.layer

  def run(request: Request): ZIO[AccountService & AccountTransferService, Nothing, Response] =
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

  def transfer(from: String, to: String, amount: String) =
    run(
      Request.put(
        "/api/accounts/transfer",
        Body.fromString(s"""{"fromAccountNumber": "$from", "toAccountNumber": "$to", "amount": $amount}""")
      )
    )

  def balances = run(Request.get("/api/accounts"))
    .flatMap(decode[Seq[AccountResponse]])
    .map(_.map(account => account.accountNumber -> account.balance).toMap)

  def assertError(response: Response, expected: String) =
    for {
      error <- decode[ErrorResponse](response)
    } yield assertTrue(
      response.status == Status.BadRequest,
      response.header(Header.ContentType).exists(_.mediaType == MediaType.application.json),
      error == ErrorResponse(expected)
    )

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
      postAccount("A", "-5.00", "GBP").flatMap(assertError(_, "CannotOpenAccountWithNegativeBalance"))
    },
    test("rejects an opening balance with more decimal places than the currency allows") {
      for {
        gbp <- postAccount("A", "5.123", "GBP").flatMap(assertError(_, "AmountHasTooManyDecimalPlaces"))
        jpy <- postAccount("B", "100.5", "JPY").flatMap(assertError(_, "AmountHasTooManyDecimalPlaces"))
      } yield gbp && jpy
    },
    test("rejects an unknown currency") {
      postAccount("A", "10", "ZZZ").flatMap(assertError(_, "UnknownCurrency"))
    },
    test("rejects a duplicate account") {
      for {
        _      <- postAccount("A", "10", "GBP")
        result <- postAccount("A", "10", "GBP").flatMap(assertError(_, "AccountAlreadyExists"))
      } yield result
    },
    test("rejects a body that is not valid JSON for the endpoint, as JSON when the client accepts it") {
      for {
        response <- run(
                      Request
                        .put("/api/accounts/transfer", Body.fromString("""{"amount": "x"}"""))
                        .addHeader(Header.Accept(MediaType.application.json))
                    )
      } yield assertTrue(
        response.status == Status.BadRequest,
        response.header(Header.ContentType).exists(_.mediaType == MediaType.application.json)
      )
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
        result <- transfer("A", "B", "0.0001").flatMap(assertError(_, "AmountHasTooManyDecimalPlaces"))
        after  <- balances
      } yield result && assertTrue(after == Map("A" -> BigDecimal(10), "B" -> BigDecimal(0)))
    },
    test("returns transfer errors as JSON") {
      for {
        _              <- postAccount("A", "10", "GBP")
        _              <- postAccount("B", "10", "EUR")
        missing        <- transfer("X", "A", "1").flatMap(assertError(_, "AccountDoesNotExist"))
        sameAccount    <- transfer("A", "A", "1").flatMap(assertError(_, "CannotTransferToSameAccount"))
        otherCurrency  <- transfer("A", "B", "1").flatMap(assertError(_, "CannotTransferToAccountWithDifferentCurrency"))
        negativeAmount <- transfer("A", "B", "-1").flatMap(assertError(_, "CannotTransferNegativeAmount"))
      } yield missing && sameAccount && otherCurrency && negativeAmount
    },
    test("rejects a transfer the source account cannot cover") {
      for {
        _      <- postAccount("A", "10", "GBP")
        _      <- postAccount("B", "0", "GBP")
        result <- transfer("A", "B", "10.01").flatMap(assertError(_, "AccountHasInsufficientFunds"))
      } yield result
    },
    test("describes the error body in the OpenAPI spec") {
      val spec = AccountRoutes.openAPISpec.toJson
      assertTrue(spec.contains("ErrorResponse"), spec.contains("TransferRequest"))
    }
  ).provide(testLayer, Runtime.removeDefaultLoggers >>> ZTestLogger.default)
}
