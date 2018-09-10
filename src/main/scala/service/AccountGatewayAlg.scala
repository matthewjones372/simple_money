package service

import model.CurrencyAccount

import scala.language.higherKinds

trait AccountGatewayAlg[F[_]] extends TransferServiceErrors {

  def getAllAccounts: F[Seq[CurrencyAccount]]

  def getAccount(iban: String): F[Either[TransferServiceErrors, CurrencyAccount]]

  def updateAccount(account: CurrencyAccount): F[Either[TransferServiceErrors, Unit]]
}
