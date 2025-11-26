package infrastructure.dataStores

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock

import cats.Eval
import domain.algebra.AccountGatewayAlg
import domain.model.{AccountNumber, AtomicTransferResult, CurrencyAccount}
import service.TransferServiceErrors
import service.TransferServiceErrors._

import scala.collection.concurrent
import scala.jdk.CollectionConverters._

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

  override def modifyAccountsAtomically(
      fromAccountNumber: AccountNumber,
      toAccountNumber: AccountNumber
  )(
      update: (CurrencyAccount, CurrencyAccount) => Either[TransferServiceErrors, (CurrencyAccount, CurrencyAccount)]
  ): Eval[Either[TransferServiceErrors, AtomicTransferResult]] =
    Eval.now {
      lock.lock()
      try {
        for {
          fromAccount <- currencyAccounts
            .get(fromAccountNumber.value)
            .toRight(AccountDoesNotExist)
          toAccount <- currencyAccounts
            .get(toAccountNumber.value)
            .toRight(AccountDoesNotExist)
          updated <- update(fromAccount, toAccount)
        } yield {
          val (updatedFrom, updatedTo) = updated
          currencyAccounts.update(fromAccountNumber.value, updatedFrom)
          currencyAccounts.update(toAccountNumber.value, updatedTo)
          AtomicTransferResult(fromAccount, toAccount, updatedFrom, updatedTo)
        }
      } finally {
        lock.unlock()
      }
    }

  private val lock = new ReentrantLock()
  private val currencyAccounts: concurrent.Map[String, CurrencyAccount] =
    new ConcurrentHashMap[String, CurrencyAccount]().asScala
}
