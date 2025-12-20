package service

import domain.{AccountNumber, CurrencyAccount, CurrencyAmount}
import java.util.Currency
import zio._
import zio.test._
import zio.test.Assertion._
import service.AccountService
import service.TransferServiceErrors._

object AccountTransferServiceSpec extends ZIOSpecDefault {

  val testLayer: ULayer[AccountService & AccountTransferService] =
    AccountService.layer ++ AccountTransferService.layer

  val gbp: Currency = Currency.getInstance("GBP")
  val eur: Currency = Currency.getInstance("EUR")

  val accountWithPositiveFunds = AccountNumber("ACCOUNT_WITH_POSITIVE")
  val positiveAccount          = CurrencyAccount(accountWithPositiveFunds, CurrencyAmount(100), gbp)

  val accountWithGBP = AccountNumber("ACCOUNT_WITH_GBP")
  val GBPAccount     = CurrencyAccount(accountWithGBP, CurrencyAmount(200), gbp)

  val accountWithEur = AccountNumber("ACCOUNT_WITH_EUR")
  val EURAccount     = CurrencyAccount(accountWithEur, CurrencyAmount(503.4), eur)

  val accountWithNegativeFunds = AccountNumber("ACCOUNT_WITH_NEGATIVE_FUNDS")
  val negativeAccount          = CurrencyAccount(accountWithNegativeFunds, CurrencyAmount(-19.99), gbp)

  def setupAccounts: ZIO[AccountService, TransferServiceErrors, Unit] = for {
    _ <- AccountService.postAccount(positiveAccount)
    _ <- AccountService.postAccount(GBPAccount)
    _ <- AccountService.postAccount(EURAccount)
    _ <- AccountService.postAccount(negativeAccount)
  } yield ()

  def spec: Spec[Any, Any] = suite("AccountTransferService")(
    test("listAllAccounts should return a seq of currency accounts") {
      for {
        _       <- setupAccounts
        results <- AccountTransferService.listAllAccounts
      } yield assertTrue(
        results.size == 4,
        results.exists(_.accountNumber == accountWithPositiveFunds)
      )
    }.provide(testLayer),
    test("transferBetweenAccounts should update both accounts if sufficient funds are present") {
      for {
        _               <- setupAccounts
        _               <- AccountTransferService.accountTransfer(accountWithPositiveFunds, accountWithGBP, CurrencyAmount(5))
        updatedAccount1 <- AccountService.getAccount(accountWithPositiveFunds)
        updatedAccount2 <- AccountService.getAccount(accountWithGBP)
      } yield assertTrue(
        updatedAccount1.balance == CurrencyAmount(95),
        updatedAccount2.balance == CurrencyAmount(205)
      )
    }.provide(testLayer),
    test("should allow multiple payments") {
      for {
        _               <- setupAccounts
        _               <- AccountTransferService.accountTransfer(accountWithPositiveFunds, accountWithGBP, CurrencyAmount(10))
        _               <- AccountTransferService.accountTransfer(accountWithPositiveFunds, accountWithGBP, CurrencyAmount(10))
        _               <- AccountTransferService.accountTransfer(accountWithPositiveFunds, accountWithGBP, CurrencyAmount(10))
        _               <- AccountTransferService.accountTransfer(accountWithPositiveFunds, accountWithGBP, CurrencyAmount(10))
        _               <- AccountTransferService.accountTransfer(accountWithPositiveFunds, accountWithGBP, CurrencyAmount(10))
        updatedAccount1 <- AccountService.getAccount(accountWithPositiveFunds)
        updatedAccount2 <- AccountService.getAccount(accountWithGBP)
      } yield assertTrue(
        updatedAccount1.balance == CurrencyAmount(50),
        updatedAccount2.balance == CurrencyAmount(250)
      )
    }.provide(testLayer),
    test("should not transfer funds when an account does not exist") {
      val nonExistingAccount = AccountNumber("SOME_NON_EXISTING_ACCOUNT")
      for {
        _      <- setupAccounts
        result <- AccountTransferService.accountTransfer(nonExistingAccount, accountWithGBP, CurrencyAmount(2)).either
      } yield assertTrue(result == Left(AccountDoesNotExist))
    }.provide(testLayer),
    test("should not transfer funds when a negative transfer is requested") {
      for {
        _ <- setupAccounts
        result <-
          AccountTransferService.accountTransfer(accountWithGBP, accountWithPositiveFunds, CurrencyAmount(-100)).either
      } yield assertTrue(result == Left(CannotTransferNegativeAmount))
    }.provide(testLayer),
    test("should not transfer funds when there is no account to transfer to") {
      val nonExistingAccount = AccountNumber("SOME_NON_EXISTING_ACCOUNT")
      for {
        _ <- setupAccounts
        result <-
          AccountTransferService.accountTransfer(accountWithPositiveFunds, nonExistingAccount, CurrencyAmount(2)).either
        account <- AccountService.getAccount(accountWithPositiveFunds)
      } yield assertTrue(
        result == Left(AccountDoesNotExist),
        account.balance == CurrencyAmount(100.0)
      )
    }.provide(testLayer),
    test("should not transfer when funds are not sufficient") {
      for {
        _ <- setupAccounts
        result <- AccountTransferService
                    .accountTransfer(accountWithNegativeFunds, accountWithPositiveFunds, CurrencyAmount(200))
                    .either
        account1 <- AccountService.getAccount(accountWithNegativeFunds)
        account2 <- AccountService.getAccount(accountWithPositiveFunds)
      } yield assertTrue(
        result == Left(AccountHasInsufficientFunds),
        account1.balance == CurrencyAmount(-19.99),
        account2.balance == CurrencyAmount(100)
      )
    }.provide(testLayer),
    test("should not be able to transfer between the same account") {
      for {
        _ <- setupAccounts
        result <- AccountTransferService
                    .accountTransfer(accountWithPositiveFunds, accountWithPositiveFunds, CurrencyAmount(30))
                    .either
        account <- AccountService.getAccount(accountWithPositiveFunds)
      } yield assertTrue(
        result == Left(CannotTransferToSameAccount),
        account.balance == CurrencyAmount(100)
      )
    }.provide(testLayer),
    test("should not be able to transfer between different currencies") {
      for {
        _      <- setupAccounts
        result <- AccountTransferService.accountTransfer(accountWithGBP, accountWithEur, CurrencyAmount(30)).either
      } yield assertTrue(result == Left(CannotTransferToAccountWithDifferentCurrency))
    }.provide(testLayer),
    test("should complete concurrent transfers without losing funds") {
      val fromAccount = AccountNumber("CONCURRENT_FROM")
      val toAccount   = AccountNumber("CONCURRENT_TO")

      for {
        _ <- AccountService.postAccount(CurrencyAccount(fromAccount, CurrencyAmount(500), gbp))
        _ <- AccountService.postAccount(CurrencyAccount(toAccount, CurrencyAmount(0), gbp))
        transfers = ZIO.foreachPar(1 to 100) { _ =>
                      AccountTransferService.accountTransfer(fromAccount, toAccount, CurrencyAmount(1))
                    }
        _           <- transfers
        fromBalance <- AccountService.getAccount(fromAccount).map(_.balance)
        toBalance   <- AccountService.getAccount(toAccount).map(_.balance)
      } yield assertTrue(
        fromBalance == CurrencyAmount(400),
        toBalance == CurrencyAmount(100)
      )
    }.provide(testLayer)
  ) @@ TestAspect.sequential
}
