package service

import domain.TestAccounts.accountOf
import domain.{AccountId, AccountNumber, CurrencyAccount, IdempotencyKey, Money, TransferOutcome}
import java.util.Currency
import zio.*
import zio.test.*
import AccountError.*

object AccountServiceSpec extends ZIOSpecDefault:

  val testLayer: ULayer[AccountStore & AccountService] =
    AccountStore.layer >+> AccountService.layer

  // Each call is a new transfer, so it gets a fresh idempotency key
  def accountTransfer(
    from: AccountNumber,
    to: AccountNumber,
    amount: BigDecimal
  ): ZIO[AccountService, AccountError, TransferOutcome] =
    Random.nextUUID.flatMap(uuid => AccountService.accountTransfer(IdempotencyKey(uuid.toString), from, to, amount))

  val gbp: Currency = Currency.getInstance("GBP")
  val eur: Currency = Currency.getInstance("EUR")

  val accountWithPositiveFunds = AccountNumber("ACCOUNT_WITH_POSITIVE")
  val positiveAccount          = accountOf(accountWithPositiveFunds, Money(100, gbp))

  val accountWithGBP = AccountNumber("ACCOUNT_WITH_GBP")
  val GBPAccount     = accountOf(accountWithGBP, Money(200, gbp))

  val accountWithEur = AccountNumber("ACCOUNT_WITH_EUR")
  val EURAccount     = accountOf(accountWithEur, Money(503.4, eur))

  val accountWithNegativeFunds = AccountNumber("ACCOUNT_WITH_NEGATIVE_FUNDS")
  val negativeAccount          = accountOf(accountWithNegativeFunds, Money(-19.99, gbp))

  def setupAccounts: ZIO[AccountStore, AccountError, Unit] = for
    _ <- AccountStore.postAccount(positiveAccount)
    _ <- AccountStore.postAccount(GBPAccount)
    _ <- AccountStore.postAccount(EURAccount)
    _ <- AccountStore.postAccount(negativeAccount)
  yield ()

  def spec = suite("AccountServiceSpec")(
    test("listAccounts should return every account when they fit on one page") {
      for
        _    <- setupAccounts
        page <- AccountService.listAccounts(None, 100)
      yield assertTrue(
        page.accounts.size == 4,
        page.accounts.exists(_.accountNumber == accountWithPositiveFunds),
        page.next.isEmpty
      )
    }.provide(testLayer),
    test("listAccounts should page through accounts in account number order") {
      for
        _      <- setupAccounts
        first  <- AccountService.listAccounts(None, 3)
        second <- AccountService.listAccounts(first.next, 3)
      yield assertTrue(
        first.accounts.map(_.accountNumber) ==
          Seq(accountWithEur, accountWithGBP, accountWithNegativeFunds),
        first.next.contains(negativeAccount.id),
        second.accounts.map(_.accountNumber) == Seq(accountWithPositiveFunds),
        second.next.isEmpty
      )
    }.provide(testLayer),
    test("listAccounts should refuse a cursor that is not an account's id") {
      for
        _      <- setupAccounts
        result <- AccountService.listAccounts(Some(AccountId(java.util.UUID.randomUUID())), 3).either
      yield assertTrue(result == Left(InvalidCursor))
    }.provide(testLayer),
    test("openAccount should give each account its own id, and find it by id or number") {
      for
        first    <- AccountService.openAccount(AccountNumber("NEW_1"), Money(1, gbp))
        second   <- AccountService.openAccount(AccountNumber("NEW_2"), Money(2, gbp))
        byId     <- AccountService.getAccount(first.id)
        byNumber <- AccountService.findAccount(AccountNumber("NEW_2"))
        missing  <- AccountService.getAccount(AccountId(java.util.UUID.randomUUID())).either
      yield assertTrue(
        first.id != second.id,
        byId == first,
        byNumber == second,
        missing == Left(AccountDoesNotExist)
      )
    }.provide(testLayer),
    test("listAccounts should refuse a page size outside 1 to 1000") {
      for
        zero    <- AccountService.listAccounts(None, 0).either
        tooMany <- AccountService.listAccounts(None, 1001).either
        largest <- AccountService.listAccounts(None, 1000).either
      yield assertTrue(
        zero == Left(InvalidPageSize),
        tooMany == Left(InvalidPageSize),
        largest.isRight
      )
    }.provide(testLayer),
    test("transferBetweenAccounts should update both accounts if sufficient funds are present") {
      for
        _               <- setupAccounts
        _               <- accountTransfer(accountWithPositiveFunds, accountWithGBP, BigDecimal(5))
        updatedAccount1 <- AccountStore.getAccount(accountWithPositiveFunds)
        updatedAccount2 <- AccountStore.getAccount(accountWithGBP)
      yield assertTrue(
        updatedAccount1.balance.amount == BigDecimal(95),
        updatedAccount2.balance.amount == BigDecimal(205)
      )
    }.provide(testLayer),
    test("logs a transfer without whole account numbers or balances") {
      for
        _ <- setupAccounts
        _ <- AccountService.accountTransfer(
               IdempotencyKey("logged"),
               accountWithPositiveFunds,
               accountWithGBP,
               BigDecimal(5)
             )
        _ <- AccountService.accountTransfer(
               IdempotencyKey("logged"),
               accountWithPositiveFunds,
               accountWithGBP,
               BigDecimal(5)
             )
        output  <- ZTestLogger.logOutput
        messages = output.map(_.message())
      yield assertTrue(
        messages.contains("Transfer logged moved 5 GBP from ****TIVE to ****_GBP"),
        messages.contains("Transfer logged was already applied, so it was not applied again"),
        !messages.exists(message =>
          message.contains(accountWithPositiveFunds.value) || message.contains(accountWithGBP.value) ||
            message.contains("95") || message.contains("205")
        )
      )
    }.provide(testLayer),
    test("should refuse an opening balance or transfer amount at or above the maximum") {
      val largest = BigDecimal("999999999999999.99")
      for
        _         <- setupAccounts
        tooLarge  <- AccountService.openAccount(AccountNumber("BIG"), Money(BigDecimal("1e15"), gbp)).either
        justBelow <-
          AccountService.openAccount(AccountNumber("BIG"), Money(largest, gbp)).either
        transfer   <- accountTransfer(AccountNumber("BIG"), accountWithGBP, BigDecimal("1e15")).either
        bigAccount <- AccountStore.getAccount(AccountNumber("BIG"))
      yield assertTrue(
        tooLarge == Left(AmountTooLarge),
        justBelow.isRight,
        transfer == Left(AmountTooLarge),
        bigAccount.balance.amount == largest
      )
    }.provide(testLayer),
    test("should keep every penny when balances grow past the maximum") {
      val largest = BigDecimal("999999999999999.99")
      val numbers = (1 to 3).map(n => AccountNumber(s"LARGE_$n"))
      for
        _        <- ZIO.foreachDiscard(numbers)(n => AccountService.openAccount(n, Money(largest, gbp)))
        _        <- accountTransfer(numbers(0), numbers(2), largest)
        _        <- accountTransfer(numbers(1), numbers(2), largest)
        _        <- accountTransfer(numbers(2), numbers(0), BigDecimal("0.01"))
        balances <- ZIO.foreach(numbers)(n => AccountStore.getAccount(n).map(_.balance.amount))
      yield assertTrue(
        balances == Seq(BigDecimal("0.01"), BigDecimal(0), BigDecimal("2999999999999999.96")),
        balances.sum == largest * 3
      )
    }.provide(testLayer),
    test("should allow multiple payments") {
      for
        _               <- setupAccounts
        _               <- accountTransfer(accountWithPositiveFunds, accountWithGBP, BigDecimal(10))
        _               <- accountTransfer(accountWithPositiveFunds, accountWithGBP, BigDecimal(10))
        _               <- accountTransfer(accountWithPositiveFunds, accountWithGBP, BigDecimal(10))
        _               <- accountTransfer(accountWithPositiveFunds, accountWithGBP, BigDecimal(10))
        _               <- accountTransfer(accountWithPositiveFunds, accountWithGBP, BigDecimal(10))
        updatedAccount1 <- AccountStore.getAccount(accountWithPositiveFunds)
        updatedAccount2 <- AccountStore.getAccount(accountWithGBP)
      yield assertTrue(
        updatedAccount1.balance.amount == BigDecimal(50),
        updatedAccount2.balance.amount == BigDecimal(250)
      )
    }.provide(testLayer),
    test("should not transfer funds when an account does not exist") {
      val nonExistingAccount = AccountNumber("SOME_NON_EXISTING_ACCOUNT")
      for
        _      <- setupAccounts
        result <- accountTransfer(nonExistingAccount, accountWithGBP, BigDecimal(2)).either
      yield assertTrue(result == Left(AccountDoesNotExist))
    }.provide(testLayer),
    test("should not transfer funds when a negative transfer is requested") {
      for
        _      <- setupAccounts
        result <-
          accountTransfer(accountWithGBP, accountWithPositiveFunds, BigDecimal(-100)).either
      yield assertTrue(result == Left(TransferAmountNotPositive))
    }.provide(testLayer),
    test("should not transfer funds when there is no account to transfer to") {
      val nonExistingAccount = AccountNumber("SOME_NON_EXISTING_ACCOUNT")
      for
        _      <- setupAccounts
        result <-
          accountTransfer(accountWithPositiveFunds, nonExistingAccount, BigDecimal(2)).either
        account <- AccountStore.getAccount(accountWithPositiveFunds)
      yield assertTrue(
        result == Left(AccountDoesNotExist),
        account.balance.amount == BigDecimal(100.0)
      )
    }.provide(testLayer),
    test("should not transfer when funds are not sufficient") {
      for
        _        <- setupAccounts
        result   <- accountTransfer(accountWithNegativeFunds, accountWithPositiveFunds, BigDecimal(200)).either
        account1 <- AccountStore.getAccount(accountWithNegativeFunds)
        account2 <- AccountStore.getAccount(accountWithPositiveFunds)
      yield assertTrue(
        result == Left(AccountHasInsufficientFunds),
        account1.balance.amount == BigDecimal(-19.99),
        account2.balance.amount == BigDecimal(100)
      )
    }.provide(testLayer),
    test("should not be able to transfer between the same account") {
      for
        _       <- setupAccounts
        result  <- accountTransfer(accountWithPositiveFunds, accountWithPositiveFunds, BigDecimal(30)).either
        account <- AccountStore.getAccount(accountWithPositiveFunds)
      yield assertTrue(
        result == Left(CannotTransferToSameAccount),
        account.balance.amount == BigDecimal(100)
      )
    }.provide(testLayer),
    test("should not be able to transfer between different currencies") {
      for
        _      <- setupAccounts
        result <- accountTransfer(accountWithGBP, accountWithEur, BigDecimal(30)).either
      yield assertTrue(result == Left(CannotTransferToAccountWithDifferentCurrency))
    }.provide(testLayer),
    test("should complete concurrent transfers without losing funds") {
      val fromAccount = AccountNumber("CONCURRENT_FROM")
      val toAccount   = AccountNumber("CONCURRENT_TO")

      for
        _        <- AccountStore.postAccount(accountOf(fromAccount, Money(500, gbp)))
        _        <- AccountStore.postAccount(accountOf(toAccount, Money(0, gbp)))
        transfers = ZIO.foreachPar(1 to 100) { _ =>
                      accountTransfer(fromAccount, toAccount, BigDecimal(1))
                    }
        _           <- transfers
        fromBalance <- AccountStore.getAccount(fromAccount).map(_.balance.amount)
        toBalance   <- AccountStore.getAccount(toAccount).map(_.balance.amount)
      yield assertTrue(
        fromBalance == BigDecimal(400),
        toBalance == BigDecimal(100)
      )
    }.provide(testLayer),
    test("should apply concurrent retries of one transfer once") {
      val fromAccount = AccountNumber("RETRIED_FROM")
      val toAccount   = AccountNumber("RETRIED_TO")

      for
        _        <- AccountStore.postAccount(accountOf(fromAccount, Money(500, gbp)))
        _        <- AccountStore.postAccount(accountOf(toAccount, Money(0, gbp)))
        outcomes <- ZIO.foreachPar(1 to 100) { _ =>
                      AccountService.accountTransfer(
                        IdempotencyKey("retried"),
                        fromAccount,
                        toAccount,
                        BigDecimal(1)
                      )
                    }
        fromBalance <- AccountStore.getAccount(fromAccount).map(_.balance.amount)
        toBalance   <- AccountStore.getAccount(toAccount).map(_.balance.amount)
      yield assertTrue(
        outcomes.count(_.isInstanceOf[TransferOutcome.Applied]) == 1,
        fromBalance == BigDecimal(499),
        toBalance == BigDecimal(1)
      )
    }.provide(testLayer),
    test("should never overdraw when concurrent transfers ask for more than the balance") {
      val fromAccount = AccountNumber("OVERDRAWN_FROM")
      val toAccount   = AccountNumber("OVERDRAWN_TO")

      for
        _       <- AccountStore.postAccount(accountOf(fromAccount, Money(100, gbp)))
        _       <- AccountStore.postAccount(accountOf(toAccount, Money(0, gbp)))
        results <- ZIO.foreachPar(1 to 250) { _ =>
                     accountTransfer(fromAccount, toAccount, BigDecimal(1)).either
                   }
        fromBalance <- AccountStore.getAccount(fromAccount).map(_.balance.amount)
        toBalance   <- AccountStore.getAccount(toAccount).map(_.balance.amount)
      yield assertTrue(
        results.count(_.isRight) == 100,
        results.collect { case Left(error) => error }.forall(_ == AccountHasInsufficientFunds),
        fromBalance == BigDecimal(0),
        toBalance == BigDecimal(100)
      )
    }.provide(testLayer),
    test("should keep the total when concurrent transfers run in both directions") {
      val first     = AccountNumber("BOTH_WAYS_1")
      val second    = AccountNumber("BOTH_WAYS_2")
      val transfers =
        List.fill(200)((first, second, BigDecimal(3))) ++ List.fill(200)((second, first, BigDecimal(2)))

      for
        _        <- AccountStore.postAccount(accountOf(first, Money(100, gbp)))
        _        <- AccountStore.postAccount(accountOf(second, Money(100, gbp)))
        shuffled <- Random.shuffle(transfers)
        results  <- ZIO.foreachPar(shuffled) { case (from, to, amount) =>
                     accountTransfer(from, to, amount).either.map(result => (from, result))
                   }
        firstBalance     <- AccountStore.getAccount(first).map(_.balance.amount)
        secondBalance    <- AccountStore.getAccount(second).map(_.balance.amount)
        appliedFromFirst  = results.count { case (from, result) => from == first && result.isRight }
        appliedFromSecond = results.count { case (from, result) => from == second && result.isRight }
      yield assertTrue(
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
