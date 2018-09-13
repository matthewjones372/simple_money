# Simple Money

Currency transfers between accounts within a business.

### Assumptions



* Transfers are system to system, no authentication has been implemented.

* All validation of account variables is done by some external API feeding the data store.

* Transfers are only made between internal accounts, no need to contact a third party.

* Transfers are only made between accounts with the same currency.

### Prerequisites

* [SBT](https://www.scala-sbt.org/) - Interactive build tool, installation instructions can be found [here](https://www.scala-sbt.org/1.x/docs/Setup.html)


### Building

```
$sbt compile
```

### Running

```
$sbt run
```
*   Will launch a demo server on http://localhost:8081

## Running the tests

```
$sbt test
```
Example:
```
[info] InMemoryEvalDataStoreUnitTest:
[info] InMemoryAccountDataStore
[info] - ListAllAccounts should list accounts correctly
[info]   GetAccount should
[info]   - return the correct account
[info]   - return AccountDoesNotExist when a non existing account is requested
[info]   UpdateAccount should
[info]   - only update an existing account
[info]   - update the correct account
[info]   PostAccount should
[info]   - post a new account into the Datastore
[info]   - not post an account that already exists
```

### API Resources

  - GET /api/accounts
  - POST /api/accounts
  - PUT /api/accounts/transfer
### Request & Response Examples

####GET /api/accounts

Gets all accounts int the datastore.

Example: http://example.com/api/accounts

Response body:

```
[
    {
        "accountNumber": "GB29 NWBK 3242 1331 9268 19",
        "balance": 423.1,
        "currency": {
            "type": "GBP"
        }
    },
    {
        "accountNumber": "GB29 NWBK 7039 1331 9268 19",
        "balance": 10002.1,
        "currency": {
            "type": "GBP"
        }
      }
   }
]
```

####POST /api/accounts

Posts a new account into the datastore

Example: http://example.com/api/accounts

Input body:
```
{
  "accountNumber": "GB29 NWBK 6016 1331 3282 19",
  "balance": 50.0,
  "currency": {
    "type": "GBP"
  }
}
```

Response body on success:
```
{
    "response": "Successfully added GB29 NWBK 6016 1331 3282 19 into the Datastore"
}
```

Response body on failure:
```
{
    "response":"Could not add GB29 NWBK 6016 1331 3282 19 into the data store reason: AccountAlreadyExists"
}
```

####PUT /api/accounts/transfer

Transfer funds between accounts

Example: http://example.com/api/accounts/transfer

Input body:
```
{
	"fromAccountNumber": "GB29 NWBK 6016 1331 3282 19",
	"toAccountNumber": "GB29 NWBK 3242 1331 9268 19",
	"amount": 58.60
}
```

Response body on success:
```
{
    "response": "Transfer unsuccessful from account GB29 NWBK 6016 1331 3282 19 to GB29 NWBK 3242 1331 9268 19 error: AccountHasInsufficientFunds"
}
```

Response body on failure:
```
{
    "response": "Transfer unsuccessful from account GB29 NWBK 6016 1331 3282 19 to GB29 NWBK 3242 1331 9268 19 error: AccountHasInsufficientFunds"
}
```

### Design
* The account data structure has been kept simple for the initial design. 
In a realistic situation this would undoubtedly be more complicated.
```
final case class CurrencyAccount(
    accountNumber: String,
    balance: Double,
    currency: Currency
)
```

* Business logic is kept separate from the infrastructure such as the datastore. 
This allows for a different Monad effect to be implemented without changing the business logic. 

* An example of a concrete TransferService has been implemented using the Cats Eval Monad, this was to demonstrate the
classes usage with something other than a Future. 

* Akka HTTP has been used to the REST interface. 


#### Nice to haves
* An account lineage attached to the account, would show what transfer occurred at which time.
* Transfer between different currency types, could be achieved from an external service such as a micro-service.

### Authors
* **Matthew Jones**

