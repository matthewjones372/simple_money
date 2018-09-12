package infrastructure.dataStores

import java.util.concurrent.ConcurrentHashMap

import cats.Eval
import model.CurrencyAccount
import service.{AccountGatewayAlg, TransferServiceErrors}

import scala.collection.JavaConverters._
import scala.collection.concurrent

class InMemoryEvalDataStore(accounts: Seq[CurrencyAccount]) extends AccountGatewayAlg[Eval] {

  override def getAllAccounts: Eval[Seq[CurrencyAccount]] = Eval.now {
    currencyAccounts.values.toVector
  }

  override def getAccount(iban: String): Eval[Either[TransferServiceErrors, CurrencyAccount]] =
    Eval.now {
      currencyAccounts
        .get(iban)
        .map(Right(_))
        .getOrElse(Left(AccountDoesNotExist))
    }

  override def updateAccount(account: CurrencyAccount): Eval[Either[TransferServiceErrors, Unit]] =
    Eval.now {
      currencyAccounts.put(account.iban, account) match {
        case Some(_) => Right(())
        case None => Left(FailedToUpdateAccount)
      }
    }

  private val currencyAccounts: concurrent.Map[String, CurrencyAccount] =
    new ConcurrentHashMap[String, CurrencyAccount]().asScala

  // Add accounts to Map
  accounts.foreach { account =>
    currencyAccounts.update(account.iban, account)
  }
}
