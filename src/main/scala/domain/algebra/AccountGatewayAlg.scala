package domain.algebra
import domain.model.{AccountNumber, AtomicTransferResult, CurrencyAccount}
import service.TransferServiceErrors

trait AccountGatewayAlg[F[_]] {

  def getAllAccounts: F[Seq[CurrencyAccount]]

  def getAccount(accountNumber: AccountNumber): F[Either[TransferServiceErrors, CurrencyAccount]]

  def updateAccount(account: CurrencyAccount): F[Either[TransferServiceErrors, Unit]]

  def postAccount(account: CurrencyAccount): F[Either[TransferServiceErrors, Unit]]

  def modifyAccountsAtomically(
      fromAccountNumber: AccountNumber,
      toAccountNumber: AccountNumber
  )(
      update: (CurrencyAccount, CurrencyAccount) => Either[TransferServiceErrors, (CurrencyAccount, CurrencyAccount)]
  ): F[Either[TransferServiceErrors, AtomicTransferResult]]
}
