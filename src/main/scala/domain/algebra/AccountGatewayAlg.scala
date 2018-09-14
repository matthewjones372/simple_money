package domain.algebra
import domain.model.CurrencyAccount
import service.TransferServiceErrors

import scala.language.higherKinds

trait AccountGatewayAlg[F[_]] extends TransferServiceErrors {

  def getAllAccounts: F[Seq[CurrencyAccount]]

  def getAccount(accountNumber: String): F[Either[TransferServiceErrors, CurrencyAccount]]

  def updateAccount(account: CurrencyAccount): F[Either[TransferServiceErrors, Unit]]

  def postAccount(account: CurrencyAccount): F[Either[TransferServiceErrors, Unit]]
}
