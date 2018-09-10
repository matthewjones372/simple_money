package infrastructure.dataStores

import java.util.concurrent.ConcurrentHashMap

import cats.Eval
import cats.implicits._
import model.CurrencyAccount
import service.{ AccountGatewayAlg, TransferServiceErrors }

import scala.collection.JavaConverters._
import scala.collection.concurrent
import scala.util.Try

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
      Try(currencyAccounts.update(account.iban, account)).toEither
        .leftMap(_ => FailedToUpdateAccount)
    }

  private val currencyAccounts: concurrent.Map[String, CurrencyAccount] =
    new ConcurrentHashMap[String, CurrencyAccount]().asScala

  // Add accounts to Map
  accounts.foreach { account =>
    currencyAccounts.update(account.iban, account)
  }
}
