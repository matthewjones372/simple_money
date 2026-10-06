package service

import domain.{
  AccountId,
  AccountNumber,
  AccountPage,
  CurrencyAccount,
  IdempotencyKey,
  Money,
  TransferInstruction,
  TransferOutcome
}
import zio.*
import service.AccountError.*

final class AccountService(accounts: AccountStore):

  def getAccount(id: AccountId): IO[AccountDoesNotExist.type, CurrencyAccount] =
    accounts.getAccountById(id)

  def findAccount(accountNumber: AccountNumber): IO[AccountDoesNotExist.type, CurrencyAccount] =
    accounts.getAccount(accountNumber)

  /**
   * Accounts in account number order, starting after the account whose id is
   * `after`, at most `limit` of them
   */
  def listAccounts(after: Option[AccountId], limit: Int): IO[Invalid, AccountPage] =
    for
      _      <- ZIO.fromEither(validPageSize(limit))
      cursor <- ZIO.foreach(after)(id => accounts.getAccountById(id).mapBoth(_ => InvalidCursor, _.accountNumber))
      all    <- accounts.getAllAccounts
    yield
      val remaining = all
        .sortBy(_.accountNumber.value)
        .filter(account => cursor.forall(number => account.accountNumber.value > number.value))
      val page = remaining.take(limit)
      AccountPage(page, if remaining.sizeIs > limit then page.lastOption.map(_.id) else None)

  /** Opens an account with a new id, and returns it */
  def openAccount(
    accountNumber: AccountNumber,
    balance: Money
  ): IO[Invalid | AccountAlreadyExists.type, CurrencyAccount] =
    for
      _      <- ZIO.fromEither(validAccountNumber(accountNumber))
      _      <- ZIO.fromEither(nonNegativeOpeningBalance(balance))
      _      <- ZIO.fromEither(withinMaximum(balance.amount))
      _      <- ZIO.fromEither(fitsMinorUnit(balance))
      id     <- Random.nextUUID.map(AccountId(_))
      account = CurrencyAccount(id, accountNumber, balance)
      _      <- accounts.postAccount(account)
    yield account

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

  private def validPageSize(limit: Int): Either[Invalid, Unit] =
    if limit >= 1 && limit <= AccountService.maxPageSize then Right(())
    else Left(InvalidPageSize)

  private def validAccountNumber(accountNumber: AccountNumber): Either[Invalid, Unit] =
    if accountNumber.isValid then Right(())
    else Left(InvalidAccountNumber)

  private def positiveTransferAmount(transferAmount: BigDecimal): Either[Invalid, Unit] =
    if transferAmount > 0 then Right(())
    else Left(TransferAmountNotPositive)

  private def nonNegativeOpeningBalance(balance: Money): Either[Invalid, Unit] =
    if !balance.isNegative then Right(())
    else Left(CannotOpenAccountWithNegativeBalance)

  private def withinMaximum(amount: BigDecimal): Either[Invalid, Unit] =
    if Money.isWithinMaximum(amount) then Right(())
    else Left(AmountTooLarge)

  private def fitsMinorUnit(amount: Money): Either[Invalid, Unit] =
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

  def getAccount(id: AccountId): ZIO[AccountService, AccountDoesNotExist.type, CurrencyAccount] =
    ZIO.serviceWithZIO[AccountService](_.getAccount(id))

  def findAccount(accountNumber: AccountNumber): ZIO[AccountService, AccountDoesNotExist.type, CurrencyAccount] =
    ZIO.serviceWithZIO[AccountService](_.findAccount(accountNumber))

  def listAccounts(
    after: Option[AccountId],
    limit: Int
  ): ZIO[AccountService, Invalid, AccountPage] =
    ZIO.serviceWithZIO[AccountService](_.listAccounts(after, limit))

  def openAccount(
    accountNumber: AccountNumber,
    balance: Money
  ): ZIO[AccountService, Invalid | AccountAlreadyExists.type, CurrencyAccount] =
    ZIO.serviceWithZIO[AccountService](_.openAccount(accountNumber, balance))

  def accountTransfer(
    key: IdempotencyKey,
    fromAccountNumber: AccountNumber,
    toAccountNumber: AccountNumber,
    transferAmount: BigDecimal
  ): ZIO[AccountService, AccountError, TransferOutcome] =
    ZIO.serviceWithZIO[AccountService](
      _.accountTransfer(key, fromAccountNumber, toAccountNumber, transferAmount)
    )
