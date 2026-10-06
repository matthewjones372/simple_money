package infrastructure

import domain.{
  AccountNumber,
  AccountTransfer,
  CurrencyAccount,
  IdempotencyKey,
  Money,
  TransferInstruction,
  TransferOutcome
}
import java.util.Currency
import zio._
import zio.test._
import zio.test.Assertion._
import service.AccountService
import service.AccountError._

object AccountServiceSpec extends ZIOSpecDefault {

  val gbp: Currency = Currency.getInstance("GBP")
  val testAccount   = CurrencyAccount(AccountNumber("TEST_ACCOUNT"), Money(100, gbp))

  def moveTwentyFive(from: CurrencyAccount, to: CurrencyAccount) =
    Right(
      (
        from.copy(balance = from.balance - Money(25, from.currency)),
        to.copy(balance = to.balance + Money(25, to.currency))
      )
    )

  def spec = suite("AccountServiceSpec")(
    test("should mask account numbers when printed") {
      val number = AccountNumber("GB29 NWBK 6016 1331 3282 19")
      assertTrue(
        number.masked == "****8219",
        number.toString == "AccountNumber(****8219)",
        testAccount.toString.contains("****OUNT"),
        !testAccount.toString.contains("TEST_ACCOUNT")
      )
    },
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
      val account1 = CurrencyAccount(AccountNumber("ACCOUNT_1"), Money(100, gbp))
      val account2 = CurrencyAccount(AccountNumber("ACCOUNT_2"), Money(200, gbp))

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
    test("should atomically modify two accounts") {
      val account1 = CurrencyAccount(AccountNumber("ATOMIC_1"), Money(100, gbp))
      val account2 = CurrencyAccount(AccountNumber("ATOMIC_2"), Money(50, gbp))

      for {
        _      <- AccountService.postAccount(account1)
        _      <- AccountService.postAccount(account2)
        result <- AccountService.transfer(
                    IdempotencyKey("atomic"),
                    TransferInstruction(account1.accountNumber, account2.accountNumber, BigDecimal(25))
                  )(moveTwentyFive)
        updated1 <- AccountService.getAccount(account1.accountNumber)
        updated2 <- AccountService.getAccount(account2.accountNumber)
      } yield assertTrue(
        updated1.balance.amount == BigDecimal(75),
        updated2.balance.amount == BigDecimal(75),
        result == TransferOutcome.Applied(
          AccountTransfer(account1, account2, updated1, updated2)
        )
      )
    },
    test("should not apply a transfer twice for the same idempotency key") {
      val account1    = CurrencyAccount(AccountNumber("REPLAY_1"), Money(100, gbp))
      val account2    = CurrencyAccount(AccountNumber("REPLAY_2"), Money(50, gbp))
      val instruction = TransferInstruction(account1.accountNumber, account2.accountNumber, BigDecimal(25))

      for {
        _        <- AccountService.postAccount(account1)
        _        <- AccountService.postAccount(account2)
        first    <- AccountService.transfer(IdempotencyKey("replay"), instruction)(moveTwentyFive)
        second   <- AccountService.transfer(IdempotencyKey("replay"), instruction)(moveTwentyFive)
        updated1 <- AccountService.getAccount(account1.accountNumber)
      } yield assertTrue(
        first.isInstanceOf[TransferOutcome.Applied],
        second == TransferOutcome.Replayed(first.asInstanceOf[TransferOutcome.Applied].transfer),
        updated1.balance.amount == BigDecimal(75)
      )
    },
    test("should refuse an idempotency key reused for a different transfer") {
      val account1 = CurrencyAccount(AccountNumber("REUSE_1"), Money(100, gbp))
      val account2 = CurrencyAccount(AccountNumber("REUSE_2"), Money(50, gbp))

      for {
        _ <- AccountService.postAccount(account1)
        _ <- AccountService.postAccount(account2)
        _ <- AccountService.transfer(
               IdempotencyKey("reuse"),
               TransferInstruction(account1.accountNumber, account2.accountNumber, BigDecimal(25))
             )(moveTwentyFive)
        result <- AccountService
                    .transfer(
                      IdempotencyKey("reuse"),
                      TransferInstruction(account1.accountNumber, account2.accountNumber, BigDecimal(30))
                    )(moveTwentyFive)
                    .either
        updated1 <- AccountService.getAccount(account1.accountNumber)
      } yield assertTrue(
        result == Left(IdempotencyKeyReusedForDifferentTransfer),
        updated1.balance.amount == BigDecimal(75)
      )
    },
    test("should not record a failed transfer, so the same key can be retried") {
      val account1    = CurrencyAccount(AccountNumber("RETRY_1"), Money(100, gbp))
      val account2    = CurrencyAccount(AccountNumber("RETRY_2"), Money(50, gbp))
      val instruction = TransferInstruction(account1.accountNumber, account2.accountNumber, BigDecimal(25))

      for {
        _      <- AccountService.postAccount(account1)
        _      <- AccountService.postAccount(account2)
        failed <- AccountService
                    .transfer(IdempotencyKey("retry"), instruction)((_, _) => Left(AccountHasInsufficientFunds))
                    .either
        retried <- AccountService.transfer(IdempotencyKey("retry"), instruction)(moveTwentyFive)
      } yield assertTrue(
        failed == Left(AccountHasInsufficientFunds),
        retried.isInstanceOf[TransferOutcome.Applied]
      )
    }
  ).provide(AccountService.layer)
    .provideLayer(
      Runtime.removeDefaultLoggers >>> ZTestLogger.default
    )
}
