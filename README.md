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

Requires [sbt](https://www.scala-sbt.org/) and a JDK.

```
sbt run
```

The server listens on http://localhost:8081, with Swagger UI at http://localhost:8081/docs.

```
sbt test
```

## API

| Method | Path | Body |
|---|---|---|
| GET | `/api/accounts` | |
| POST | `/api/accounts` | `accountNumber`, `balance`, `currencyCode` |
| PUT | `/api/accounts/transfer` | `fromAccountNumber`, `toAccountNumber`, `amount` |

### Create an account

```
curl -X POST localhost:8081/api/accounts -H 'Content-Type: application/json' -d '{
  "accountNumber": "GB29 NWBK 6016 1331 3282 19",
  "balance": 50.00,
  "currencyCode": "GBP"
}'
```

```json
{"message": "Successfully added GB29 NWBK 6016 1331 3282 19 into the datastore"}
```

`currencyCode` is an ISO 4217 code. The balance cannot be negative or have more decimal places than the currency
allows, so `50.001` GBP is refused. Failures return `400 Bad Request` with an error body:

```json
{"error": "AmountHasTooManyDecimalPlaces"}
```

The errors are `UnknownCurrency`, `AccountAlreadyExists`, `CannotOpenAccountWithNegativeBalance` and
`AmountHasTooManyDecimalPlaces`.

### List accounts

```
curl localhost:8081/api/accounts
```

```json
[
  {"accountNumber": "GB29 NWBK 6016 1331 3282 19", "balance": 50.00, "currencyCode": "GBP"},
  {"accountNumber": "GB29 NWBK 3242 1331 9268 19", "balance": 423.10, "currencyCode": "GBP"}
]
```

### Transfer

```
curl -X PUT localhost:8081/api/accounts/transfer -H 'Content-Type: application/json' -d '{
  "fromAccountNumber": "GB29 NWBK 3242 1331 9268 19",
  "toAccountNumber": "GB29 NWBK 6016 1331 3282 19",
  "amount": 58.60
}'
```

```json
{"message": "58.60 has been transferred from GB29 NWBK 3242 1331 9268 19 to GB29 NWBK 6016 1331 3282 19"}
```

A transfer that cannot be made returns `400 Bad Request` with one of these errors:

- `AccountDoesNotExist`
- `AmountHasTooManyDecimalPlaces`, for an amount smaller than the currency's minor unit
- `AccountHasInsufficientFunds`
- `CannotTransferToSameAccount`
- `CannotTransferToAccountWithDifferentCurrency`
- `CannotTransferNegativeAmount`

## Design

- Layers: `AccountService` holds the accounts, and `AccountTransferService` holds the transfer rules. Both are
  ZLayers, so the tests can provide their own.
- Atomic transfers: Accounts live in a `TMap`. A transfer reads both accounts, checks the rules and writes both
  balances in one STM transaction, so concurrent transfers cannot lose or create money. One of the tests runs many
  transfers at once and checks the total is unchanged.
- Errors as values: Failures are a sealed `TransferServiceErrors` type in the ZIO error channel rather than
  exceptions.
- OpenAPI: The routes are implemented from ZIO HTTP endpoints, so the Swagger page describes what the server does.
  Requests must be JSON. A body that does not decode gets ZIO HTTP's own codec error, as JSON when the client sends
  `Accept: application/json`.

The account model is deliberately small:

```scala
final case class CurrencyAccount(
  accountNumber: AccountNumber,
  balance: CurrencyAmount,
  currency: Currency
)
```

## Possible next steps

- A history of transfers on each account.
- Transfers between currencies, using an external rates service.

## License

MIT. See [LICENSE](LICENSE).
