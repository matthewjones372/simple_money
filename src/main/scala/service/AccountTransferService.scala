package service

import java.text.DecimalFormat

import cats.Monad
import cats.data.EitherT
import model.CurrencyAccount

import scala.language.higherKinds

class AccountTransferService[F[_]](
    gateway: AccountGatewayAlg[F],
    logger: LoggingAlg[F]
)(implicit ME: Monad[F]) {

  import ME._

  def listAllAccounts: F[Seq[CurrencyAccount]] =
    gateway.getAllAccounts

  def accountTransfer(fromIban: String,
                      toIban: String,
                      transferAmount: Double): F[Either[TransferServiceErrors, Unit]] = {

    def lift[A](fa: F[A]): EitherT[F, TransferServiceErrors, A] =
      EitherT.liftF[F, TransferServiceErrors, A](fa)

    (for {
      _           <- EitherT(pure(areDifferentAccounts(fromIban, toIban)))
      fromAccount <- EitherT(gateway.getAccount(fromIban))
      toAccount   <- EitherT(gateway.getAccount(toIban))

      _ <- EitherT(pure(haveSameCurrency(fromAccount, toAccount)))
      _ <- EitherT(pure(hasSufficientBalance(fromAccount, transferAmount)))

      updatedFromAccount <- lift(pure(subtractBalance(fromAccount, transferAmount)))
      updatedToAccount   <- lift(pure(addBalance(toAccount, transferAmount)))

      _ <- lift(pure(gateway.updateAccount(updatedFromAccount)))
      _ <- lift(logger.info(updatedLogMessage(fromAccount, updatedFromAccount)))
      _ <- lift(pure(gateway.updateAccount(updatedToAccount)))
      _ <- lift(logger.info(updatedLogMessage(toAccount, updatedToAccount)))
    } yield ()).value
  }

  private def subtractBalance(fromAccount: CurrencyAccount,
                              transferAmount: Double): CurrencyAccount =
    fromAccount.copy(balance = fromAccount.balance - transferAmount)

  private def addBalance(toAccount: CurrencyAccount, transferAmount: Double): CurrencyAccount =
    toAccount.copy(balance = toAccount.balance + transferAmount)

  private def updatedLogMessage(before: CurrencyAccount, after: CurrencyAccount) = {
    val currency  = before.currency.symbol
    val formatter = new DecimalFormat("#.##")
    s"Updated Account: ${before.iban}: Balance updated from $currency${formatter.format(before.balance)} " +
    s"to $currency${formatter.format(after.balance)}"
  }

  private def hasSufficientBalance(account: CurrencyAccount,
                                   transferAmount: Double): Either[TransferServiceErrors, Unit] =
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

  private def areDifferentAccounts(fromIban: String,
                                   toIban: String): Either[TransferServiceErrors, Unit] =
    if (!fromIban.equals(toIban)) {
      Right(())
    } else {
      Left(gateway.CannotTransferToSameAccount)
    }
}
