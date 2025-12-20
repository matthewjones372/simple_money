package service

import domain.{AccountNumber, CurrencyAccount, CurrencyAmount}
import zio._
import service.AccountService
import service.TransferServiceErrors._

case class AccountTransferService() {

  def listAllAccounts: URIO[AccountService, Seq[CurrencyAccount]] =
    AccountService.getAllAccounts

  def addNewAccount(account: CurrencyAccount): ZIO[AccountService, TransferServiceErrors, Unit] =
    AccountService.postAccount(account)

  def accountTransfer(
    fromAccountNumber: AccountNumber,
    toAccountNumber: AccountNumber,
    transferAmount: CurrencyAmount
  ): ZIO[AccountService, TransferServiceErrors, Unit] =
    for {
      _ <- ZIO.fromEither(nonNegativeTransferAmount(transferAmount))
      _ <- ZIO.fromEither(areDifferentAccounts(fromAccountNumber, toAccountNumber))
      transferResult <- AccountService.transfer(fromAccountNumber, toAccountNumber) { (fromAccount, toAccount) =>
                          for {
                            _ <- haveSameCurrency(fromAccount, toAccount)
                            _ <- hasSufficientBalance(fromAccount, transferAmount)
                          } yield (
                            subtractBalance(fromAccount, transferAmount),
                            addBalance(toAccount, transferAmount)
                          )
                        }
      _ <- ZIO.logInfo(updatedLogMessage(transferResult.fromBefore, transferResult.fromAfter))
      _ <- ZIO.logInfo(updatedLogMessage(transferResult.toBefore, transferResult.toAfter))
    } yield ()

  private def subtractBalance(fromAccount: CurrencyAccount, transferAmount: CurrencyAmount): CurrencyAccount =
    fromAccount.copy(balance = fromAccount.balance - transferAmount)

  private def addBalance(toAccount: CurrencyAccount, transferAmount: CurrencyAmount): CurrencyAccount =
    toAccount.copy(balance = toAccount.balance + transferAmount)

  private def updatedLogMessage(before: CurrencyAccount, after: CurrencyAccount) =
    s"""Updated Account: ${before.accountNumber.value}: Balance updated from ${before.balance.value} """ +
      s"""${before.currency.getCurrencyCode} to ${after.balance.value} ${after.currency.getCurrencyCode}"""

  private def nonNegativeTransferAmount(transferAmount: CurrencyAmount): Either[TransferServiceErrors, Unit] =
    if (transferAmount.value > 0) {
      Right(())
    } else {
      Left(CannotTransferNegativeAmount)
    }

  private def hasSufficientBalance(
    account: CurrencyAccount,
    transferAmount: CurrencyAmount
  ): Either[TransferServiceErrors, Unit] =
    if (account.balance >= transferAmount) {
      Right(())
    } else {
      Left(AccountHasInsufficientFunds)
    }

  private def haveSameCurrency(
    toAccount: CurrencyAccount,
    fromAccount: CurrencyAccount
  ): Either[TransferServiceErrors, Unit] =
    if (toAccount.currency.equals(fromAccount.currency)) {
      Right(())
    } else {
      Left(CannotTransferToAccountWithDifferentCurrency)
    }

  private def areDifferentAccounts(
    fromAccountNumber: AccountNumber,
    toAccountNumber: AccountNumber
  ): Either[TransferServiceErrors, Unit] =
    if (fromAccountNumber != toAccountNumber) {
      Right(())
    } else {
      Left(CannotTransferToSameAccount)
    }
}

object AccountTransferService {
  val layer: ULayer[AccountTransferService] = ZLayer.succeed(AccountTransferService())

  def listAllAccounts: URIO[AccountService & AccountTransferService, Seq[CurrencyAccount]] =
    ZIO.serviceWithZIO[AccountTransferService](_.listAllAccounts)

  def addNewAccount(
    account: CurrencyAccount
  ): ZIO[AccountService & AccountTransferService, TransferServiceErrors, Unit] =
    ZIO.serviceWithZIO[AccountTransferService](_.addNewAccount(account))

  def accountTransfer(
    fromAccountNumber: AccountNumber,
    toAccountNumber: AccountNumber,
    transferAmount: CurrencyAmount
  ): ZIO[AccountService & AccountTransferService, TransferServiceErrors, Unit] =
    ZIO.serviceWithZIO[AccountTransferService](_.accountTransfer(fromAccountNumber, toAccountNumber, transferAmount))
}
