package service

import domain.{AccountNumber, CurrencyAccount, CurrencyAmount, IdempotencyKey, TransferOutcome}
import java.util.Currency
import zio._
import zio.test._
import zio.test.Assertion._
import service.AccountService
import service.TransferServiceErrors._

object AccountServiceHttpSpec extends ZIOSpecDefault {

  val testLayer: ULayer[AccountService & AccountTransferService] =
    AccountService.layer >+> AccountTransferService.layer

  // Each call is a new transfer, so it gets a fresh idempotency key
  def accountTransfer(
    from: AccountNumber,
    to: AccountNumber,
    amount: CurrencyAmount
  ): ZIO[AccountTransferService, TransferServiceErrors, TransferOutcome] =
    Random.nextUUID.flatMap(uuid =>
      AccountTransferService.accountTransfer(IdempotencyKey(uuid.toString), from, to, amount)
    )

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

  def spec = suite("AccountServiceHttpSpec")(
    test("listAccounts should return every account when they fit on one page") {
      for {
        _    <- setupAccounts
        page <- AccountTransferService.listAccounts(None, 100)
      } yield assertTrue(
        page.accounts.size == 4,
        page.accounts.exists(_.accountNumber == accountWithPositiveFunds),
        page.next.isEmpty
      )
    }.provide(testLayer),
    test("listAccounts should page through accounts in account number order") {
      for {
        _      <- setupAccounts
        first  <- AccountTransferService.listAccounts(None, 3)
        second <- AccountTransferService.listAccounts(first.next, 3)
      } yield assertTrue(
        first.accounts.map(_.accountNumber) ==
          Seq(accountWithEur, accountWithGBP, accountWithNegativeFunds),
        first.next.contains(accountWithNegativeFunds),
        second.accounts.map(_.accountNumber) == Seq(accountWithPositiveFunds),
        second.next.isEmpty
      )
    }.provide(testLayer),
    test("listAccounts should refuse a page size outside 1 to 1000") {
      for {
        zero    <- AccountTransferService.listAccounts(None, 0).either
        tooMany <- AccountTransferService.listAccounts(None, 1001).either
        largest <- AccountTransferService.listAccounts(None, 1000).either
      } yield assertTrue(
        zero == Left(InvalidPageSize),
        tooMany == Left(InvalidPageSize),
        largest.isRight
      )
    }.provide(testLayer),
    test("transferBetweenAccounts should update both accounts if sufficient funds are present") {
      for {
        _               <- setupAccounts
        _               <- accountTransfer(accountWithPositiveFunds, accountWithGBP, CurrencyAmount(5))
        updatedAccount1 <- AccountService.getAccount(accountWithPositiveFunds)
        updatedAccount2 <- AccountService.getAccount(accountWithGBP)
      } yield assertTrue(
        updatedAccount1.balance == CurrencyAmount(95),
        updatedAccount2.balance == CurrencyAmount(205)
      )
    }.provide(testLayer),
    test("logs a transfer without whole account numbers or balances") {
      for {
        _ <- setupAccounts
        _ <- AccountTransferService.accountTransfer(
               IdempotencyKey("logged"),
               accountWithPositiveFunds,
               accountWithGBP,
               CurrencyAmount(5)
             )
        _ <- AccountTransferService.accountTransfer(
               IdempotencyKey("logged"),
               accountWithPositiveFunds,
               accountWithGBP,
               CurrencyAmount(5)
             )
        output  <- ZTestLogger.logOutput
        messages = output.map(_.message())
      } yield assertTrue(
        messages.contains("Transfer logged moved 5 GBP from ****TIVE to ****_GBP"),
        messages.contains("Transfer logged was already applied, so it was not applied again"),
        !messages.exists(message =>
          message.contains(accountWithPositiveFunds.value) || message.contains(accountWithGBP.value) ||
            message.contains("95") || message.contains("205")
        )
      )
    }.provide(testLayer),
    test("should allow multiple payments") {
      for {
        _               <- setupAccounts
        _               <- accountTransfer(accountWithPositiveFunds, accountWithGBP, CurrencyAmount(10))
        _               <- accountTransfer(accountWithPositiveFunds, accountWithGBP, CurrencyAmount(10))
        _               <- accountTransfer(accountWithPositiveFunds, accountWithGBP, CurrencyAmount(10))
        _               <- accountTransfer(accountWithPositiveFunds, accountWithGBP, CurrencyAmount(10))
        _               <- accountTransfer(accountWithPositiveFunds, accountWithGBP, CurrencyAmount(10))
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
        result <- accountTransfer(nonExistingAccount, accountWithGBP, CurrencyAmount(2)).either
      } yield assertTrue(result == Left(AccountDoesNotExist))
    }.provide(testLayer),
    test("should not transfer funds when a negative transfer is requested") {
      for {
        _      <- setupAccounts
        result <-
          accountTransfer(accountWithGBP, accountWithPositiveFunds, CurrencyAmount(-100)).either
      } yield assertTrue(result == Left(CannotTransferNegativeAmount))
    }.provide(testLayer),
    test("should not transfer funds when there is no account to transfer to") {
      val nonExistingAccount = AccountNumber("SOME_NON_EXISTING_ACCOUNT")
      for {
        _      <- setupAccounts
        result <-
          accountTransfer(accountWithPositiveFunds, nonExistingAccount, CurrencyAmount(2)).either
        account <- AccountService.getAccount(accountWithPositiveFunds)
      } yield assertTrue(
        result == Left(AccountDoesNotExist),
        account.balance == CurrencyAmount(100.0)
      )
    }.provide(testLayer),
    test("should not transfer when funds are not sufficient") {
      for {
        _        <- setupAccounts
        result   <- accountTransfer(accountWithNegativeFunds, accountWithPositiveFunds, CurrencyAmount(200)).either
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
        _       <- setupAccounts
        result  <- accountTransfer(accountWithPositiveFunds, accountWithPositiveFunds, CurrencyAmount(30)).either
        account <- AccountService.getAccount(accountWithPositiveFunds)
      } yield assertTrue(
        result == Left(CannotTransferToSameAccount),
        account.balance == CurrencyAmount(100)
      )
    }.provide(testLayer),
    test("should not be able to transfer between different currencies") {
      for {
        _      <- setupAccounts
        result <- accountTransfer(accountWithGBP, accountWithEur, CurrencyAmount(30)).either
      } yield assertTrue(result == Left(CannotTransferToAccountWithDifferentCurrency))
    }.provide(testLayer),
    test("should complete concurrent transfers without losing funds") {
      val fromAccount = AccountNumber("CONCURRENT_FROM")
      val toAccount   = AccountNumber("CONCURRENT_TO")

      for {
        _        <- AccountService.postAccount(CurrencyAccount(fromAccount, CurrencyAmount(500), gbp))
        _        <- AccountService.postAccount(CurrencyAccount(toAccount, CurrencyAmount(0), gbp))
        transfers = ZIO.foreachPar(1 to 100) { _ =>
                      accountTransfer(fromAccount, toAccount, CurrencyAmount(1))
                    }
        _           <- transfers
        fromBalance <- AccountService.getAccount(fromAccount).map(_.balance)
        toBalance   <- AccountService.getAccount(toAccount).map(_.balance)
      } yield assertTrue(
        fromBalance == CurrencyAmount(400),
        toBalance == CurrencyAmount(100)
      )
    }.provide(testLayer),
    test("should apply concurrent retries of one transfer once") {
      val fromAccount = AccountNumber("RETRIED_FROM")
      val toAccount   = AccountNumber("RETRIED_TO")

      for {
        _        <- AccountService.postAccount(CurrencyAccount(fromAccount, CurrencyAmount(500), gbp))
        _        <- AccountService.postAccount(CurrencyAccount(toAccount, CurrencyAmount(0), gbp))
        outcomes <- ZIO.foreachPar(1 to 100) { _ =>
                      AccountTransferService.accountTransfer(
                        IdempotencyKey("retried"),
                        fromAccount,
                        toAccount,
                        CurrencyAmount(1)
                      )
                    }
        fromBalance <- AccountService.getAccount(fromAccount).map(_.balance)
        toBalance   <- AccountService.getAccount(toAccount).map(_.balance)
      } yield assertTrue(
        outcomes.count(_.isInstanceOf[TransferOutcome.Applied]) == 1,
        fromBalance == CurrencyAmount(499),
        toBalance == CurrencyAmount(1)
      )
    }.provide(testLayer),
    test("should never overdraw when concurrent transfers ask for more than the balance") {
      val fromAccount = AccountNumber("OVERDRAWN_FROM")
      val toAccount   = AccountNumber("OVERDRAWN_TO")

      for {
        _       <- AccountService.postAccount(CurrencyAccount(fromAccount, CurrencyAmount(100), gbp))
        _       <- AccountService.postAccount(CurrencyAccount(toAccount, CurrencyAmount(0), gbp))
        results <- ZIO.foreachPar(1 to 250) { _ =>
                     accountTransfer(fromAccount, toAccount, CurrencyAmount(1)).either
                   }
        fromBalance <- AccountService.getAccount(fromAccount).map(_.balance)
        toBalance   <- AccountService.getAccount(toAccount).map(_.balance)
      } yield assertTrue(
        results.count(_.isRight) == 100,
        results.collect { case Left(error) => error }.forall(_ == AccountHasInsufficientFunds),
        fromBalance == CurrencyAmount(0),
        toBalance == CurrencyAmount(100)
      )
    }.provide(testLayer),
    test("should keep the total when concurrent transfers run in both directions") {
      val first     = AccountNumber("BOTH_WAYS_1")
      val second    = AccountNumber("BOTH_WAYS_2")
      val transfers =
        List.fill(200)((first, second, CurrencyAmount(3))) ++ List.fill(200)((second, first, CurrencyAmount(2)))

      for {
        _        <- AccountService.postAccount(CurrencyAccount(first, CurrencyAmount(100), gbp))
        _        <- AccountService.postAccount(CurrencyAccount(second, CurrencyAmount(100), gbp))
        shuffled <- Random.shuffle(transfers)
        results  <- ZIO.foreachPar(shuffled) { case (from, to, amount) =>
                     accountTransfer(from, to, amount).either.map(result => (from, result))
                   }
        firstBalance     <- AccountService.getAccount(first).map(_.balance.value)
        secondBalance    <- AccountService.getAccount(second).map(_.balance.value)
        appliedFromFirst  = results.count { case (from, result) => from == first && result.isRight }
        appliedFromSecond = results.count { case (from, result) => from == second && result.isRight }
      } yield assertTrue(
        firstBalance + secondBalance == BigDecimal(200),
        firstBalance >= 0,
        secondBalance >= 0,
        firstBalance == BigDecimal(100) - 3 * appliedFromFirst + 2 * appliedFromSecond,
        results.collect { case (_, Left(error)) => error }.forall(_ == AccountHasInsufficientFunds)
      )
    }.provide(testLayer) @@ TestAspect.nonFlaky(20)
  ).provideLayer(
    Runtime.removeDefaultLoggers >>> ZTestLogger.default
  )
}
