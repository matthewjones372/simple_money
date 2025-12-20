package infrastructure

import domain.{AccountNumber, CurrencyAccount, CurrencyAmount}
import java.util.Currency
import zio._
import zio.test._
import zio.test.Assertion._
import service.AccountService
import service.TransferServiceErrors._

object InMemoryDataStoreSpec extends ZIOSpecDefault {

  val gbp: Currency = Currency.getInstance("GBP")
  val testAccount   = CurrencyAccount(AccountNumber("TEST_ACCOUNT"), CurrencyAmount(100), gbp)

  def spec: Spec[Any, Any] = suite("InMemoryDataStore")(
    test("should successfully post a new account") {
      for {
        _         <- AccountService.postAccount(testAccount)
        retrieved <- AccountService.getAccount(testAccount.accountNumber)
      } yield assertTrue(retrieved == testAccount)
    },
    test("should fail to post duplicate account") {
      for {
        _      <- AccountService.postAccount(testAccount)
        result <- AccountService.postAccount(testAccount).either
      } yield assertTrue(result == Left(AccountAlreadyExists))
    },
    test("should retrieve all accounts") {
      val account1 = CurrencyAccount(AccountNumber("ACCOUNT_1"), CurrencyAmount(100), gbp)
      val account2 = CurrencyAccount(AccountNumber("ACCOUNT_2"), CurrencyAmount(200), gbp)

      for {
        _   <- AccountService.postAccount(account1)
        _   <- AccountService.postAccount(account2)
        all <- AccountService.getAllAccounts
      } yield assertTrue(
        all.size == 2,
        all.contains(account1),
        all.contains(account2)
      )
    },
    test("should fail to get non-existent account") {
      for {
        result <- AccountService.getAccount(AccountNumber("NON_EXISTENT")).either
      } yield assertTrue(result == Left(AccountDoesNotExist))
    },
    test("should update existing account") {
      val original = CurrencyAccount(AccountNumber("UPDATE_TEST"), CurrencyAmount(100), gbp)
      val updated  = original.copy(balance = CurrencyAmount(200))

      for {
        _         <- AccountService.postAccount(original)
        _         <- AccountService.updateAccount(updated)
        retrieved <- AccountService.getAccount(original.accountNumber)
      } yield assertTrue(retrieved.balance == CurrencyAmount(200))
    },
    test("should atomically modify two accounts") {
      val account1 = CurrencyAccount(AccountNumber("ATOMIC_1"), CurrencyAmount(100), gbp)
      val account2 = CurrencyAccount(AccountNumber("ATOMIC_2"), CurrencyAmount(50), gbp)

      for {
        _ <- AccountService.postAccount(account1)
        _ <- AccountService.postAccount(account2)
        result <- AccountService.transfer(account1.accountNumber, account2.accountNumber) { (from, to) =>
                    Right(
                      (
                        from.copy(balance = CurrencyAmount(from.balance.value - 25)),
                        to.copy(balance = CurrencyAmount(to.balance.value + 25))
                      )
                    )
                  }
        updated1 <- AccountService.getAccount(account1.accountNumber)
        updated2 <- AccountService.getAccount(account2.accountNumber)
      } yield assertTrue(
        updated1.balance == CurrencyAmount(75),
        updated2.balance == CurrencyAmount(75),
        result.fromBefore == account1,
        result.toBefore == account2
      )
    }
  ).provide(AccountService.layer) @@ TestAspect.sequential
}
