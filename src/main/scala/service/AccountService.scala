package service

import domain.{AccountNumber, AccountPage, CurrencyAccount, IdempotencyKey, Money, TransferInstruction, TransferOutcome}
import zio.*
import service.AccountError.*

final class AccountService(accounts: AccountStore):

  def getAccount(accountNumber: AccountNumber): IO[AccountError, CurrencyAccount] =
    accounts.getAccount(accountNumber)

  /**
   * Accounts in account number order, starting after `after`, at most `limit`
   * of them
   */
  def listAccounts(after: Option[AccountNumber], limit: Int): IO[AccountError, AccountPage] =
    for
      _   <- ZIO.fromEither(validPageSize(limit))
      all <- accounts.getAllAccounts
    yield
      val remaining = all
        .sortBy(_.accountNumber.value)
        .filter(account => after.forall(cursor => account.accountNumber.value > cursor.value))
      val page = remaining.take(limit)
      AccountPage(page, if remaining.sizeIs > limit then page.lastOption.map(_.accountNumber) else None)

  def addNewAccount(account: CurrencyAccount): IO[AccountError, Unit] =
    for
      _ <- ZIO.fromEither(nonNegativeOpeningBalance(account.balance))
      _ <- ZIO.fromEither(withinMaximum(account.balance.amount))
      _ <- ZIO.fromEither(fitsMinorUnit(account.balance))
      _ <- accounts.postAccount(account)
    yield ()

  def accountTransfer(
    key: IdempotencyKey,
    fromAccountNumber: AccountNumber,
    toAccountNumber: AccountNumber,
    transferAmount: BigDecimal
  ): IO[AccountError, TransferOutcome] =
    val instruction = TransferInstruction(fromAccountNumber, toAccountNumber, transferAmount)
    for
      _       <- ZIO.fromEither(positiveTransferAmount(transferAmount))
      _       <- ZIO.fromEither(withinMaximum(transferAmount))
      _       <- ZIO.fromEither(areDifferentAccounts(fromAccountNumber, toAccountNumber))
      outcome <- accounts.transfer(key, instruction): (fromAccount, toAccount) =>
                   val amount = Money(transferAmount, fromAccount.currency)
                   for
                     _ <- haveSameCurrency(fromAccount, toAccount)
                     _ <- fitsMinorUnit(amount)
                     _ <- hasSufficientBalance(fromAccount, amount)
                   yield (
                     fromAccount.copy(balance = fromAccount.balance - amount),
                     toAccount.copy(balance = toAccount.balance + amount)
                   )
      _ <- outcome match
             case TransferOutcome.Applied(transfer) =>
               ZIO.logInfo(
                 s"Transfer ${key.value} moved $transferAmount ${transfer.fromBefore.currency.getCurrencyCode} " +
                   s"from ${fromAccountNumber.masked} to ${toAccountNumber.masked}"
               )
             case TransferOutcome.Replayed(_) =>
               ZIO.logInfo(s"Transfer ${key.value} was already applied, so it was not applied again")
    yield outcome

  private def validPageSize(limit: Int): Either[AccountError, Unit] =
    if limit >= 1 && limit <= AccountService.maxPageSize then Right(())
    else Left(InvalidPageSize)

  private def positiveTransferAmount(transferAmount: BigDecimal): Either[AccountError, Unit] =
    if transferAmount > 0 then Right(())
    else Left(TransferAmountNotPositive)

  private def nonNegativeOpeningBalance(balance: Money): Either[AccountError, Unit] =
    if !balance.isNegative then Right(())
    else Left(CannotOpenAccountWithNegativeBalance)

  private def withinMaximum(amount: BigDecimal): Either[AccountError, Unit] =
    if Money.isWithinMaximum(amount) then Right(())
    else Left(AmountTooLarge)

  private def fitsMinorUnit(amount: Money): Either[AccountError, Unit] =
    if amount.fitsMinorUnit then Right(())
    else Left(AmountHasTooManyDecimalPlaces)

  private def hasSufficientBalance(
    account: CurrencyAccount,
    transferAmount: Money
  ): Either[AccountError, Unit] =
    if account.balance >= transferAmount then Right(())
    else Left(AccountHasInsufficientFunds)

  private def haveSameCurrency(
    fromAccount: CurrencyAccount,
    toAccount: CurrencyAccount
  ): Either[AccountError, Unit] =
    if fromAccount.currency == toAccount.currency then Right(())
    else Left(CannotTransferToAccountWithDifferentCurrency)

  private def areDifferentAccounts(
    fromAccountNumber: AccountNumber,
    toAccountNumber: AccountNumber
  ): Either[AccountError, Unit] =
    if fromAccountNumber != toAccountNumber then Right(())
    else Left(CannotTransferToSameAccount)

object AccountService:
  val layer: URLayer[AccountStore, AccountService] =
    ZLayer.fromFunction(AccountService(_))

  val maxPageSize: Int = 1000

  def getAccount(accountNumber: AccountNumber): ZIO[AccountService, AccountError, CurrencyAccount] =
    ZIO.serviceWithZIO[AccountService](_.getAccount(accountNumber))

  def listAccounts(
    after: Option[AccountNumber],
    limit: Int
  ): ZIO[AccountService, AccountError, AccountPage] =
    ZIO.serviceWithZIO[AccountService](_.listAccounts(after, limit))

  def addNewAccount(account: CurrencyAccount): ZIO[AccountService, AccountError, Unit] =
    ZIO.serviceWithZIO[AccountService](_.addNewAccount(account))

  def accountTransfer(
    key: IdempotencyKey,
    fromAccountNumber: AccountNumber,
    toAccountNumber: AccountNumber,
    transferAmount: BigDecimal
  ): ZIO[AccountService, AccountError, TransferOutcome] =
    ZIO.serviceWithZIO[AccountService](
      _.accountTransfer(key, fromAccountNumber, toAccountNumber, transferAmount)
    )
