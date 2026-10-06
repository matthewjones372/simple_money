package service

import domain.{
  AccountNumber,
  AccountPage,
  CurrencyAccount,
  CurrencyAmount,
  IdempotencyKey,
  TransferInstruction,
  TransferOutcome
}
import zio._
import service.AccountError._

import java.util.Currency

final class AccountTransferService(accounts: AccountService):

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
      _ <- ZIO.fromEither(fitsCurrency(account.balance, account.currency))
      _ <- accounts.postAccount(account)
    yield ()

  def accountTransfer(
    key: IdempotencyKey,
    fromAccountNumber: AccountNumber,
    toAccountNumber: AccountNumber,
    transferAmount: CurrencyAmount
  ): IO[AccountError, TransferOutcome] =
    val instruction = TransferInstruction(fromAccountNumber, toAccountNumber, transferAmount)
    for
      _       <- ZIO.fromEither(positiveTransferAmount(transferAmount))
      _       <- ZIO.fromEither(areDifferentAccounts(fromAccountNumber, toAccountNumber))
      outcome <- accounts.transfer(key, instruction): (fromAccount, toAccount) =>
                   for
                     _ <- haveSameCurrency(fromAccount, toAccount)
                     _ <- fitsCurrency(transferAmount, fromAccount.currency)
                     _ <- hasSufficientBalance(fromAccount, transferAmount)
                   yield (
                     subtractBalance(fromAccount, transferAmount),
                     addBalance(toAccount, transferAmount)
                   )
      _ <- outcome match
             case TransferOutcome.Applied(transfer) =>
               ZIO.logInfo(
                 s"Transfer ${key.value} moved ${transferAmount.value} ${transfer.fromBefore.currency.getCurrencyCode} " +
                   s"from ${fromAccountNumber.masked} to ${toAccountNumber.masked}"
               )
             case TransferOutcome.Replayed(_) =>
               ZIO.logInfo(s"Transfer ${key.value} was already applied, so it was not applied again")
    yield outcome

  private def subtractBalance(fromAccount: CurrencyAccount, transferAmount: CurrencyAmount): CurrencyAccount =
    fromAccount.copy(balance = fromAccount.balance - transferAmount)

  private def addBalance(toAccount: CurrencyAccount, transferAmount: CurrencyAmount): CurrencyAccount =
    toAccount.copy(balance = toAccount.balance + transferAmount)

  private def validPageSize(limit: Int): Either[AccountError, Unit] =
    if limit >= 1 && limit <= AccountTransferService.maxPageSize then Right(())
    else Left(InvalidPageSize)

  private def positiveTransferAmount(transferAmount: CurrencyAmount): Either[AccountError, Unit] =
    if transferAmount.value > 0 then Right(())
    else Left(TransferAmountNotPositive)

  private def nonNegativeOpeningBalance(balance: CurrencyAmount): Either[AccountError, Unit] =
    if balance.value >= 0 then Right(())
    else Left(CannotOpenAccountWithNegativeBalance)

  // Currencies without minor units, such as XAU, report -1 and are not limited
  private def fitsCurrency(amount: CurrencyAmount, currency: Currency): Either[AccountError, Unit] =
    val fractionDigits = currency.getDefaultFractionDigits
    if fractionDigits < 0 || amount.value.bigDecimal.stripTrailingZeros.scale <= fractionDigits then Right(())
    else Left(AmountHasTooManyDecimalPlaces)

  private def hasSufficientBalance(
    account: CurrencyAccount,
    transferAmount: CurrencyAmount
  ): Either[AccountError, Unit] =
    if account.balance >= transferAmount then Right(())
    else Left(AccountHasInsufficientFunds)

  private def haveSameCurrency(
    fromAccount: CurrencyAccount,
    toAccount: CurrencyAccount
  ): Either[AccountError, Unit] =
    if fromAccount.currency.equals(toAccount.currency) then Right(())
    else Left(CannotTransferToAccountWithDifferentCurrency)

  private def areDifferentAccounts(
    fromAccountNumber: AccountNumber,
    toAccountNumber: AccountNumber
  ): Either[AccountError, Unit] =
    if fromAccountNumber != toAccountNumber then Right(())
    else Left(CannotTransferToSameAccount)

object AccountTransferService:
  val layer: URLayer[AccountService, AccountTransferService] =
    ZLayer.fromFunction(AccountTransferService(_))

  val maxPageSize: Int = 1000

  def getAccount(accountNumber: AccountNumber): ZIO[AccountTransferService, AccountError, CurrencyAccount] =
    ZIO.serviceWithZIO[AccountTransferService](_.getAccount(accountNumber))

  def listAccounts(
    after: Option[AccountNumber],
    limit: Int
  ): ZIO[AccountTransferService, AccountError, AccountPage] =
    ZIO.serviceWithZIO[AccountTransferService](_.listAccounts(after, limit))

  def addNewAccount(account: CurrencyAccount): ZIO[AccountTransferService, AccountError, Unit] =
    ZIO.serviceWithZIO[AccountTransferService](_.addNewAccount(account))

  def accountTransfer(
    key: IdempotencyKey,
    fromAccountNumber: AccountNumber,
    toAccountNumber: AccountNumber,
    transferAmount: CurrencyAmount
  ): ZIO[AccountTransferService, AccountError, TransferOutcome] =
    ZIO.serviceWithZIO[AccountTransferService](
      _.accountTransfer(key, fromAccountNumber, toAccountNumber, transferAmount)
    )
