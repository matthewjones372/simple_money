package service

import domain.{AccountNumber, AccountTransfer, CurrencyAccount, IdempotencyKey, TransferInstruction, TransferOutcome}
import service.TransferServiceErrors.{
  AccountAlreadyExists,
  AccountDoesNotExist,
  FailedToUpdateAccount,
  IdempotencyKeyReusedForDifferentTransfer
}
import zio.*
import zio.stm.{STM, TMap}

trait AccountService:

  def getAllAccounts: UIO[Seq[CurrencyAccount]]

  def getAccount(accountNumber: AccountNumber): IO[TransferServiceErrors, CurrencyAccount]

  def updateAccount(account: CurrencyAccount): IO[TransferServiceErrors, Unit]

  def postAccount(account: CurrencyAccount): IO[TransferServiceErrors, Unit]

  /**
   * Applies `update` to both accounts in one transaction and records the transfer against `key`. A later call with
   * the same key and instruction returns the recorded transfer without applying it again.
   */
  def transfer(key: IdempotencyKey, instruction: TransferInstruction)(
    update: (CurrencyAccount, CurrencyAccount) => Either[TransferServiceErrors, (CurrencyAccount, CurrencyAccount)]
  ): IO[TransferServiceErrors, TransferOutcome]

object AccountService:
  val layer: ULayer[AccountService] =
    ZLayer:
      (TMap.empty[AccountNumber, CurrencyAccount] <*> TMap.empty[IdempotencyKey, CompletedTransfer]).commit
        .map(DefaultDataStore(_, _))

  def getAllAccounts: URIO[AccountService, Seq[CurrencyAccount]] =
    ZIO.serviceWithZIO[AccountService](_.getAllAccounts)

  def getAccount(accountNumber: AccountNumber): ZIO[AccountService, TransferServiceErrors, CurrencyAccount] =
    ZIO.serviceWithZIO[AccountService](_.getAccount(accountNumber))

  def updateAccount(account: CurrencyAccount): ZIO[AccountService, TransferServiceErrors, Unit] =
    ZIO.serviceWithZIO[AccountService](_.updateAccount(account))

  def postAccount(account: CurrencyAccount): ZIO[AccountService, TransferServiceErrors, Unit] =
    ZIO.serviceWithZIO[AccountService](_.postAccount(account))

  def transfer(key: IdempotencyKey, instruction: TransferInstruction)(
    update: (CurrencyAccount, CurrencyAccount) => Either[TransferServiceErrors, (CurrencyAccount, CurrencyAccount)]
  ): ZIO[AccountService, TransferServiceErrors, TransferOutcome] =
    ZIO.serviceWithZIO[AccountService](_.transfer(key, instruction)(update))

private final case class CompletedTransfer(instruction: TransferInstruction, transfer: AccountTransfer)

// Completed transfers are kept for the life of the process, as the accounts are
private case class DefaultDataStore(
  accounts: TMap[AccountNumber, CurrencyAccount],
  completedTransfers: TMap[IdempotencyKey, CompletedTransfer]
) extends AccountService:

  override def getAllAccounts: UIO[Seq[CurrencyAccount]] =
    accounts.values.commit

  override def getAccount(
    accountNumber: AccountNumber
  ): IO[TransferServiceErrors, CurrencyAccount] =
    accounts.get(accountNumber).commit.flatMap:
      case Some(account) => ZIO.succeed(account)
      case None          => ZIO.fail(AccountDoesNotExist)

  override def updateAccount(account: CurrencyAccount): IO[TransferServiceErrors, Unit] =
    STM.atomically:
      accounts.contains(account.accountNumber).flatMap:
        case true  => accounts.put(account.accountNumber, account)
        case false => STM.fail(FailedToUpdateAccount)

  override def postAccount(account: CurrencyAccount): IO[TransferServiceErrors, Unit] =
    STM.atomically:
      accounts.contains(account.accountNumber).flatMap:
        case true  => STM.fail(AccountAlreadyExists)
        case false => accounts.put(account.accountNumber, account)

  override def transfer(key: IdempotencyKey, instruction: TransferInstruction)(
    update: (CurrencyAccount, CurrencyAccount) => Either[TransferServiceErrors, (CurrencyAccount, CurrencyAccount)]
  ): IO[TransferServiceErrors, TransferOutcome] =
    STM.atomically:
      completedTransfers.get(key).flatMap:
        case Some(completed) if completed.instruction == instruction =>
          STM.succeed(TransferOutcome.Replayed(completed.transfer))
        case Some(_) =>
          STM.fail(IdempotencyKeyReusedForDifferentTransfer)
        case None =>
          for
            fromAccountOpt          <- accounts.get(instruction.fromAccountNumber)
            toAccountOpt            <- accounts.get(instruction.toAccountNumber)
            fromAccount             <- STM.fromOption(fromAccountOpt).orElseFail(AccountDoesNotExist)
            toAccount               <- STM.fromOption(toAccountOpt).orElseFail(AccountDoesNotExist)
            result                  <- STM.fromEither(update(fromAccount, toAccount))
            (updatedFrom, updatedTo) = result
            _                       <- accounts.put(instruction.fromAccountNumber, updatedFrom)
            _                       <- accounts.put(instruction.toAccountNumber, updatedTo)
            transfer                 = AccountTransfer(fromAccount, toAccount, updatedFrom, updatedTo)
            _                       <- completedTransfers.put(key, CompletedTransfer(instruction, transfer))
          yield TransferOutcome.Applied(transfer)
