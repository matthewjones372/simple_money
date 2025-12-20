package service

import domain.{AccountNumber, AccountTransfer, CurrencyAccount}
import service.TransferServiceErrors.{AccountAlreadyExists, AccountDoesNotExist, FailedToUpdateAccount}
import zio.*
import zio.stm.{STM, TMap}

trait AccountService {

  def getAllAccounts: UIO[Seq[CurrencyAccount]]

  def getAccount(accountNumber: AccountNumber): IO[TransferServiceErrors, CurrencyAccount]

  def updateAccount(account: CurrencyAccount): IO[TransferServiceErrors, Unit]

  def postAccount(account: CurrencyAccount): IO[TransferServiceErrors, Unit]

  def transfer(
    fromAccountNumber: AccountNumber,
    toAccountNumber: AccountNumber
  )(
    update: (CurrencyAccount, CurrencyAccount) => Either[TransferServiceErrors, (CurrencyAccount, CurrencyAccount)]
  ): IO[TransferServiceErrors, AccountTransfer]
}

object AccountService {
  val layer: ULayer[AccountService] =
    ZLayer {
      TMap.empty[AccountNumber, CurrencyAccount].commit.map(DefaultDataStore(_))
    }

  def getAllAccounts: URIO[AccountService, Seq[CurrencyAccount]] =
    ZIO.serviceWithZIO[AccountService](_.getAllAccounts)

  def getAccount(accountNumber: AccountNumber): ZIO[AccountService, TransferServiceErrors, CurrencyAccount] =
    ZIO.serviceWithZIO[AccountService](_.getAccount(accountNumber))

  def updateAccount(account: CurrencyAccount): ZIO[AccountService, TransferServiceErrors, Unit] =
    ZIO.serviceWithZIO[AccountService](_.updateAccount(account))

  def postAccount(account: CurrencyAccount): ZIO[AccountService, TransferServiceErrors, Unit] =
    ZIO.serviceWithZIO[AccountService](_.postAccount(account))

  def transfer(
    fromAccountNumber: AccountNumber,
    toAccountNumber: AccountNumber
  )(
    update: (CurrencyAccount, CurrencyAccount) => Either[TransferServiceErrors, (CurrencyAccount, CurrencyAccount)]
  ): ZIO[AccountService, TransferServiceErrors, AccountTransfer] =
    ZIO.serviceWithZIO[AccountService](_.transfer(fromAccountNumber, toAccountNumber)(update))
}

private case class DefaultDataStore(
  accounts: TMap[AccountNumber, CurrencyAccount]
) extends AccountService {

  override def getAllAccounts: UIO[Seq[CurrencyAccount]] =
    accounts.values.commit

  override def getAccount(
    accountNumber: AccountNumber
  ): IO[TransferServiceErrors, CurrencyAccount] =
    accounts.get(accountNumber).commit.flatMap {
      case Some(account) => ZIO.succeed(account)
      case None          => ZIO.fail(AccountDoesNotExist)
    }

  override def updateAccount(account: CurrencyAccount): IO[TransferServiceErrors, Unit] =
    STM.atomically {
      accounts.contains(account.accountNumber).flatMap {
        case true  => accounts.put(account.accountNumber, account)
        case false => STM.fail(FailedToUpdateAccount)
      }
    }

  override def postAccount(account: CurrencyAccount): IO[TransferServiceErrors, Unit] =
    STM.atomically {
      accounts.contains(account.accountNumber).flatMap {
        case true  => STM.fail(AccountAlreadyExists)
        case false => accounts.put(account.accountNumber, account)
      }
    }

  override def transfer(
    fromAccountNumber: AccountNumber,
    toAccountNumber: AccountNumber
  )(
    update: (CurrencyAccount, CurrencyAccount) => Either[TransferServiceErrors, (CurrencyAccount, CurrencyAccount)]
  ): IO[TransferServiceErrors, AccountTransfer] =
    STM.atomically {
      for {
        fromAccountOpt          <- accounts.get(fromAccountNumber)
        toAccountOpt            <- accounts.get(toAccountNumber)
        fromAccount             <- STM.fromOption(fromAccountOpt).orElseFail(AccountDoesNotExist)
        toAccount               <- STM.fromOption(toAccountOpt).orElseFail(AccountDoesNotExist)
        result                  <- STM.fromEither(update(fromAccount, toAccount))
        (updatedFrom, updatedTo) = result
        _                       <- accounts.put(fromAccountNumber, updatedFrom)
        _                       <- accounts.put(toAccountNumber, updatedTo)
      } yield AccountTransfer(fromAccount, toAccount, updatedFrom, updatedTo)
    }
}
