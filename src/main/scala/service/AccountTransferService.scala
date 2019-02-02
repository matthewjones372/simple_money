package service

import cats.Monad
import cats.data.EitherT
import domain.algebra.{AccountGatewayAlg, LoggingAlg}
import domain.model.{AccountNumber, CurrencyAccount, CurrencyAmount}

import scala.language.higherKinds

class AccountTransferService[F[_]](
                                    gateway: AccountGatewayAlg[F],
                                    logger: LoggingAlg[F]
                                  )(implicit M: Monad[F]) {

  import M._

  def listAllAccounts: F[Seq[CurrencyAccount]] =
    gateway.getAllAccounts

  def addNewAccount(account: CurrencyAccount): F[Either[TransferServiceErrors, Unit]] =
    gateway.postAccount(account)

  def accountTransfer(fromAccountNumber: AccountNumber,
                      toAccountNumber: AccountNumber,
                      transferAmount: CurrencyAmount): F[Either[TransferServiceErrors, Unit]] = {

    def lift[A](fa: F[A]): EitherT[F, TransferServiceErrors, A] =
      EitherT.liftF[F, TransferServiceErrors, A](fa)

    (for {
      _ <- EitherT(pure(nonNegativeTransferAmount(transferAmount)))
      _ <- EitherT(pure(areDifferentAccounts(fromAccountNumber, toAccountNumber)))
      fromAccount <- EitherT(gateway.getAccount
      (fromAccountNumber))
      toAccount <- EitherT(gateway.getAccount(toAccountNumber))

      _ <- EitherT(pure(haveSameCurrency(fromAccount, toAccount)))
      _ <- EitherT(pure(hasSufficientBalance(fromAccount, transferAmount)))

      updatedFromAccount <- lift(pure(subtractBalance(fromAccount, transferAmount)))
      updatedToAccount <- lift(pure(addBalance(toAccount, transferAmount)))

      _ <- lift(pure(gateway.updateAccount(updatedFromAccount)))
      _ <- lift(logger.info(updatedLogMessage(fromAccount, updatedFromAccount)))
      _ <- lift(pure(gateway.updateAccount(updatedToAccount)))
      _ <- lift(logger.info(updatedLogMessage(toAccount, updatedToAccount)))
    } yield ()).value
  }

  private def subtractBalance(fromAccount: CurrencyAccount,
                              transferAmount: CurrencyAmount): CurrencyAccount =
    fromAccount.copy(balance = fromAccount.balance - transferAmount)

  private def addBalance(toAccount: CurrencyAccount, transferAmount: CurrencyAmount): CurrencyAccount =
    toAccount.copy(balance = toAccount.balance + transferAmount)

  private def updatedLogMessage(before: CurrencyAccount, after: CurrencyAccount) = {
    s"""Updated Account: ${before.accountNumber.value}: Balance updated from ${before.balance.value} """ +
      s"""${before.currency.toString} to ${after.balance.value} ${after.currency.toString}"""
  }

  private def nonNegativeTransferAmount(transferAmount: CurrencyAmount): Either[TransferServiceErrors, Unit] = {

    if (transferAmount.value > 0) {
      Right(())
    } else {
      Left(gateway.CannotTransferNegativeAmount)
    }
  }

  private def hasSufficientBalance(account: CurrencyAccount,
                                   transferAmount: CurrencyAmount): Either[TransferServiceErrors, Unit] =
    if (account.balance >= transferAmount) {
      Right(())
    } else {
      Left(gateway.AccountHasInsufficientFunds)
    }

  private def haveSameCurrency(toAccount: CurrencyAccount,
                               fromAccount: CurrencyAccount): Either[TransferServiceErrors, Unit] =
    if (toAccount.currency.equals(fromAccount.currency)) {
      Right(())
    } else {
      Left(gateway.CannotTransferToAccountWithDifferentCurrency)
    }

  private def areDifferentAccounts(fromAccountNumber: AccountNumber,
                                   toAccountNumber: AccountNumber): Either[TransferServiceErrors, Unit] =
    if (fromAccountNumber != toAccountNumber) {
      Right(())
    } else {
      Left(gateway.CannotTransferToSameAccount)
    }
}
