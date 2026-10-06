# Simple Money

A small HTTP service for moving money between accounts, in Scala 3 with ZIO and ZIO HTTP. Accounts are held in memory,
and transfers are atomic using ZIO STM.

It started in 2018 as an Akka and cats project and was later rewritten on ZIO.

## Scope

- No authentication: callers are other internal systems.
- Account details are assumed to be validated before they reach the service.
- Transfers are between accounts held here, with no third party involved.
- Both accounts in a transfer must have the same currency.

## Running

Requires [sbt](https://www.scala-sbt.org/) and JDK 25 or later.

```
sbt run
```

The server listens on port 8081, with Swagger UI at http://localhost:8081/docs. Set the `PORT` environment variable
to use another port, or `PORT=0` to let the system pick a free one; the log says which port it is listening on.

```
sbt test
```

## API

| Method | Path | Body |
|---|---|---|
| GET | `/api/accounts?limit=&after=` | |
| GET | `/api/accounts/{id}` | |
| POST | `/api/accounts/lookup` | `accountNumber` |
| POST | `/api/accounts` | `accountNumber`, `balance`, `currencyCode` |
| POST | `/api/accounts/transfer` | `fromAccountNumber`, `toAccountNumber`, `amount`, plus an `Idempotency-Key` header |

Accounts are addressed in URLs by an `id` the service gives them, never by account number, so that account numbers,
which are personal data, stay out of URLs and the access logs that record them.

### Create an account

```
curl -i -X POST localhost:8081/api/accounts -H 'Content-Type: application/json' -d '{
  "accountNumber": "GB29 NWBK 6016 1331 3282 19",
  "balance": 50.00,
  "currencyCode": "GBP"
}'
```

The response is `201 Created`, with the account's address in `Location` and the account in the body:

```
Location: /api/accounts/6a190ca3-714e-40ed-be8c-f7624330cd43
```

```json
{"id": "6a190ca3-714e-40ed-be8c-f7624330cd43", "accountNumber": "GB29 NWBK 6016 1331 3282 19", "balance": 50.00, "currencyCode": "GBP"}
```

`accountNumber` must be 1 to 64 characters, with no spaces at either end and no control characters; spaces inside,
as IBANs are often written, are fine. `currencyCode` is an ISO 4217 code. The balance cannot be negative, cannot
reach 1,000,000,000,000,000 (at most 15 digits before the decimal point) and cannot have more decimal places than
the currency allows, so `50.001` GBP is refused. See [Errors](#errors) for what a failure returns.

### List accounts

```
curl 'localhost:8081/api/accounts?limit=2'
```

```json
{
  "accounts": [
    {"id": "0b6f1d7e-5f43-4d0f-9a43-7f5f4f0f6a11", "accountNumber": "GB29 NWBK 3242 1331 9268 19", "balance": 423.10, "currencyCode": "GBP"},
    {"id": "6a190ca3-714e-40ed-be8c-f7624330cd43", "accountNumber": "GB29 NWBK 6016 1331 3282 19", "balance": 50.00, "currencyCode": "GBP"}
  ],
  "next": "6a190ca3-714e-40ed-be8c-f7624330cd43"
}
```

Accounts come in account number order, `limit` at a time: 100 by default, at most 1000. When there are more, `next`
is the id of the last account on the page, to pass as `after` for the following page; on the last page it is `null`.
Paging after an account rather than by position means accounts added while paging do not shift the pages.

### Get one account

By its id, as in the `Location` from creating it:

```
curl localhost:8081/api/accounts/6a190ca3-714e-40ed-be8c-f7624330cd43
```

Or by its number, which goes in the body of a `POST` so that it stays out of the URL:

```
curl -X POST localhost:8081/api/accounts/lookup -H 'Content-Type: application/json' \
  -d '{"accountNumber": "GB29 NWBK 6016 1331 3282 19"}'
```

Either returns the account as above, or `404` with `AccountDoesNotExist`.

### Transfer

```
curl -X POST localhost:8081/api/accounts/transfer \
  -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: 6f1c2e4a-0b7d-4a51-9a3e-2d8f5c1b7e90' \
  -d '{
  "fromAccountNumber": "GB29 NWBK 3242 1331 9268 19",
  "toAccountNumber": "GB29 NWBK 6016 1331 3282 19",
  "amount": 58.60
}'
```

```json
{"message": "58.60 has been transferred from GB29 NWBK 3242 1331 9268 19 to GB29 NWBK 6016 1331 3282 19"}
```

Every transfer needs an `Idempotency-Key` header, a value the caller makes up for that transfer, such as a UUID. If a
request is retried with the same key and the same body, the transfer is not made again and the original success is
returned, so a caller can safely retry after a timeout. Reusing a key with a different body is refused. A transfer that
fails is not recorded, so retrying it with the same key tries again.

## Errors

Every failure has a JSON body with a stable `error` code to match on and a `message` for people:

```json
{"error": "AccountHasInsufficientFunds", "message": "The account does not have enough money for this transfer"}
```

| Status | `error` | When |
|---|---|---|
| 400 | `MalformedBody`, `UnsupportedContentType` | The body is not JSON of the right shape |
| 400 | `MissingHeader`, `MalformedHeader` | A required header, such as `Idempotency-Key`, is missing or unreadable |
| 400 | `MalformedQueryParam` | A query parameter is unreadable: `limit` not a number, or `after` not an id |
| 400 | `InvalidCursor` | `after` is not the id of an account |
| 400 | `InvalidPageSize` | `limit` is not between 1 and 1000 |
| 400 | `InvalidAccountNumber` | A new account's number is blank, padded, over 64 characters or has control characters |
| 400 | `UnknownCurrency` | The currency code is not ISO 4217 |
| 400 | `CannotOpenAccountWithNegativeBalance` | A new account has a negative balance |
| 400 | `AmountHasTooManyDecimalPlaces` | An amount is finer than its currency's minor unit |
| 400 | `AmountTooLarge` | An opening balance or transfer amount is 1,000,000,000,000,000 or more |
| 400 | `TransferAmountNotPositive` | A transfer amount is zero or less |
| 400 | `CannotTransferToSameAccount` | A transfer names the same account twice |
| 400 | `IdempotencyKeyIsBlank` | The `Idempotency-Key` header is blank |
| 404 | `AccountDoesNotExist` | The account, or an account in a transfer, does not exist |
| 409 | `AccountAlreadyExists` | A new account's number is already taken |
| 409 | `IdempotencyKeyReusedForDifferentTransfer` | An `Idempotency-Key` was already used for a different transfer |
| 422 | `AccountHasInsufficientFunds` | The source account cannot cover the transfer |
| 422 | `CannotTransferToAccountWithDifferentCurrency` | The two accounts have different currencies |

## Design

- Layers: `AccountStore` holds the accounts, and `AccountService` holds the rules for opening, listing and
  transferring, and is built from an `AccountStore`. Both are ZLayers, so the tests can provide their own.
- Atomic transfers: Accounts live in a `TMap`. A transfer reads both accounts, checks the rules and writes both
  balances in one STM transaction, so concurrent transfers cannot lose or create money. The same transaction records
  the transfer against its idempotency key, so concurrent retries of one transfer apply it once. Recorded keys are
  kept in memory for the life of the process, like the accounts. The tests race many transfers at once, including
  ones that together ask for more than the balance and ones running in both directions, and check that no account
  is overdrawn and the total is unchanged.
- Errors as values: Failures are a sealed `AccountError` type in the ZIO error channel rather than exceptions, in four
  kinds: invalid (400), missing (404), conflicting (409) and refused (422). Each service method's error type names
  only the kinds it can fail with, and each endpoint declares only the matching statuses, so the Swagger page lists
  exactly what an endpoint can return, and returning an undeclared status does not compile.
- OpenAPI: The routes are implemented from ZIO HTTP endpoints, so the Swagger page describes what the server does.
  Requests must be JSON. A request that does not decode gets the same error body as any other failure.

The account model is deliberately small:

```scala
final case class CurrencyAccount(accountNumber: AccountNumber, balance: Money)

final case class Money(amount: BigDecimal, currency: Currency)
```

`Money` only adds, subtracts and compares amounts of the same currency, and knows whether an amount fits its
currency's minor unit. Adding and subtracting are exact: Scala's `BigDecimal` arithmetic rounds to 34 significant
digits, so `Money` uses Java's, which does not. Balances can grow past the 15-digit limit on what comes in without
losing a penny. Amounts written in exponent form, such as `1e3`, come back as plain numbers.

## Possible next steps

- A history of transfers on each account.
- Transfers between currencies, using an external rates service.

## License

MIT. See [LICENSE](LICENSE).
