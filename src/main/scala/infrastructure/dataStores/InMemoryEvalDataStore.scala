package infrastructure.dataStores

import java.util.concurrent.ConcurrentHashMap

import cats.Eval
import domain.algebra.AccountGatewayAlg
import domain.model.{AccountNumber, CurrencyAccount}
import service.TransferServiceErrors

import scala.collection.JavaConverters._
import scala.collection.concurrent

class InMemoryEvalDataStore extends AccountGatewayAlg[Eval] {

  override def getAllAccounts: Eval[Seq[CurrencyAccount]] = Eval.now {
    currencyAccounts.values.toVector
  }

  override def getAccount(
      accountNumber: AccountNumber
  ): Eval[Either[TransferServiceErrors, CurrencyAccount]] =
    Eval.now {
      currencyAccounts
        .get(accountNumber.value)
        .map(Right(_))
        .getOrElse(Left(AccountDoesNotExist))
    }

  override def updateAccount(account: CurrencyAccount): Eval[Either[TransferServiceErrors, Unit]] =
    Eval.now {
      currencyAccounts.put(account.accountNumber.value, account) match {
        case Some(_) => Right(())
        case None    => Left(FailedToUpdateAccount)
      }
    }

  override def postAccount(account: CurrencyAccount): Eval[Either[TransferServiceErrors, Unit]] =
    Eval.now {
      if (currencyAccounts.isDefinedAt(account.accountNumber.value)) {
        Left(AccountAlreadyExists)
      } else {
        Right(currencyAccounts.update(account.accountNumber.value, account))
      }
    }

  private val currencyAccounts: concurrent.Map[String, CurrencyAccount] =
    new ConcurrentHashMap[String, CurrencyAccount]().asScala
}
