package service

import domain.{AccountNumber, CurrencyAccount, CurrencyAmount, IdempotencyKey, TransferInstruction, TransferOutcome}
import zio._
import service.TransferServiceErrors._

import java.util.Currency

final class AccountTransferService(accounts: AccountService):

  def listAllAccounts: UIO[Seq[CurrencyAccount]] =
    accounts.getAllAccounts

  def addNewAccount(account: CurrencyAccount): IO[TransferServiceErrors, Unit] =
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
  ): IO[TransferServiceErrors, TransferOutcome] =
    val instruction = TransferInstruction(fromAccountNumber, toAccountNumber, transferAmount)
    for
      _       <- ZIO.fromEither(nonNegativeTransferAmount(transferAmount))
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
               ZIO.logInfo(updatedLogMessage(transfer.fromBefore, transfer.fromAfter)) *>
                 ZIO.logInfo(updatedLogMessage(transfer.toBefore, transfer.toAfter))
             case TransferOutcome.Replayed(_) =>
               ZIO.logInfo(s"Transfer ${key.value} was already applied, so it was not applied again")
    yield outcome

  private def subtractBalance(fromAccount: CurrencyAccount, transferAmount: CurrencyAmount): CurrencyAccount =
    fromAccount.copy(balance = fromAccount.balance - transferAmount)

  private def addBalance(toAccount: CurrencyAccount, transferAmount: CurrencyAmount): CurrencyAccount =
    toAccount.copy(balance = toAccount.balance + transferAmount)

  private def updatedLogMessage(before: CurrencyAccount, after: CurrencyAccount) =
    s"""Updated Account: ${before.accountNumber.value}: Balance updated from ${before.balance.value} """ +
      s"""${before.currency.getCurrencyCode} to ${after.balance.value} ${after.currency.getCurrencyCode}"""

  private def nonNegativeTransferAmount(transferAmount: CurrencyAmount): Either[TransferServiceErrors, Unit] =
    if transferAmount.value > 0 then Right(())
    else Left(CannotTransferNegativeAmount)

  private def nonNegativeOpeningBalance(balance: CurrencyAmount): Either[TransferServiceErrors, Unit] =
    if balance.value >= 0 then Right(())
    else Left(CannotOpenAccountWithNegativeBalance)

  // Currencies without minor units, such as XAU, report -1 and are not limited
  private def fitsCurrency(amount: CurrencyAmount, currency: Currency): Either[TransferServiceErrors, Unit] =
    val fractionDigits = currency.getDefaultFractionDigits
    if fractionDigits < 0 || amount.value.bigDecimal.stripTrailingZeros.scale <= fractionDigits then Right(())
    else Left(AmountHasTooManyDecimalPlaces)

  private def hasSufficientBalance(
    account: CurrencyAccount,
    transferAmount: CurrencyAmount
  ): Either[TransferServiceErrors, Unit] =
    if account.balance >= transferAmount then Right(())
    else Left(AccountHasInsufficientFunds)

  private def haveSameCurrency(
    fromAccount: CurrencyAccount,
    toAccount: CurrencyAccount
  ): Either[TransferServiceErrors, Unit] =
    if fromAccount.currency.equals(toAccount.currency) then Right(())
    else Left(CannotTransferToAccountWithDifferentCurrency)

  private def areDifferentAccounts(
    fromAccountNumber: AccountNumber,
    toAccountNumber: AccountNumber
  ): Either[TransferServiceErrors, Unit] =
    if fromAccountNumber != toAccountNumber then Right(())
    else Left(CannotTransferToSameAccount)

object AccountTransferService:
  val layer: URLayer[AccountService, AccountTransferService] =
    ZLayer.fromFunction(AccountTransferService(_))

  def listAllAccounts: URIO[AccountTransferService, Seq[CurrencyAccount]] =
    ZIO.serviceWithZIO[AccountTransferService](_.listAllAccounts)

  def addNewAccount(account: CurrencyAccount): ZIO[AccountTransferService, TransferServiceErrors, Unit] =
    ZIO.serviceWithZIO[AccountTransferService](_.addNewAccount(account))

  def accountTransfer(
    key: IdempotencyKey,
    fromAccountNumber: AccountNumber,
    toAccountNumber: AccountNumber,
    transferAmount: CurrencyAmount
  ): ZIO[AccountTransferService, TransferServiceErrors, TransferOutcome] =
    ZIO.serviceWithZIO[AccountTransferService](
      _.accountTransfer(key, fromAccountNumber, toAccountNumber, transferAmount)
    )
