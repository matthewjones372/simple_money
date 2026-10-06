package service

import domain.{
  AccountId,
  AccountNumber,
  AccountTransfer,
  CurrencyAccount,
  IdempotencyKey,
  TransferInstruction,
  TransferOutcome
}
import service.AccountError.{AccountAlreadyExists, AccountDoesNotExist, IdempotencyKeyReusedForDifferentTransfer}
import zio.*
import zio.stm.{STM, TMap}

trait AccountStore:

  def getAllAccounts: UIO[Seq[CurrencyAccount]]

  def getAccount(accountNumber: AccountNumber): IO[AccountError, CurrencyAccount]

  def getAccountById(id: AccountId): IO[AccountError, CurrencyAccount]

  def postAccount(account: CurrencyAccount): IO[AccountError, Unit]

  /**
   * Applies `update` to both accounts in one transaction and records the
   * transfer against `key`. A later call with the same key and instruction
   * returns the recorded transfer without applying it again.
   */
  def transfer(key: IdempotencyKey, instruction: TransferInstruction)(
    update: (CurrencyAccount, CurrencyAccount) => Either[AccountError, (CurrencyAccount, CurrencyAccount)]
  ): IO[AccountError, TransferOutcome]

object AccountStore:
  val layer: ULayer[AccountStore] =
    ZLayer:
      (TMap.empty[AccountNumber, CurrencyAccount] <*> TMap.empty[AccountId, AccountNumber] <*>
        TMap.empty[IdempotencyKey, CompletedTransfer]).commit
        .map(InMemoryAccountStore(_, _, _))

  def getAllAccounts: URIO[AccountStore, Seq[CurrencyAccount]] =
    ZIO.serviceWithZIO[AccountStore](_.getAllAccounts)

  def getAccount(accountNumber: AccountNumber): ZIO[AccountStore, AccountError, CurrencyAccount] =
    ZIO.serviceWithZIO[AccountStore](_.getAccount(accountNumber))

  def getAccountById(id: AccountId): ZIO[AccountStore, AccountError, CurrencyAccount] =
    ZIO.serviceWithZIO[AccountStore](_.getAccountById(id))

  def postAccount(account: CurrencyAccount): ZIO[AccountStore, AccountError, Unit] =
    ZIO.serviceWithZIO[AccountStore](_.postAccount(account))

  def transfer(key: IdempotencyKey, instruction: TransferInstruction)(
    update: (CurrencyAccount, CurrencyAccount) => Either[AccountError, (CurrencyAccount, CurrencyAccount)]
  ): ZIO[AccountStore, AccountError, TransferOutcome] =
    ZIO.serviceWithZIO[AccountStore](_.transfer(key, instruction)(update))

private final case class CompletedTransfer(instruction: TransferInstruction, transfer: AccountTransfer)

// Completed transfers are kept for the life of the process, as the accounts are
private case class InMemoryAccountStore(
  accounts: TMap[AccountNumber, CurrencyAccount],
  accountNumbers: TMap[AccountId, AccountNumber],
  completedTransfers: TMap[IdempotencyKey, CompletedTransfer]
) extends AccountStore:

  override def getAllAccounts: UIO[Seq[CurrencyAccount]] =
    accounts.values.commit

  override def getAccount(
    accountNumber: AccountNumber
  ): IO[AccountError, CurrencyAccount] =
    accounts
      .get(accountNumber)
      .commit
      .flatMap:
        case Some(account) => ZIO.succeed(account)
        case None          => ZIO.fail(AccountDoesNotExist)

  override def getAccountById(id: AccountId): IO[AccountError, CurrencyAccount] =
    STM.atomically:
      for
        number  <- accountNumbers.get(id).someOrFail(AccountDoesNotExist)
        account <- accounts.get(number).someOrFail(AccountDoesNotExist)
      yield account

  override def postAccount(account: CurrencyAccount): IO[AccountError, Unit] =
    STM.atomically:
      accounts
        .contains(account.accountNumber)
        .flatMap:
          case true  => STM.fail(AccountAlreadyExists)
          case false =>
            accounts.put(account.accountNumber, account) *> accountNumbers.put(account.id, account.accountNumber)

  override def transfer(key: IdempotencyKey, instruction: TransferInstruction)(
    update: (CurrencyAccount, CurrencyAccount) => Either[AccountError, (CurrencyAccount, CurrencyAccount)]
  ): IO[AccountError, TransferOutcome] =
    STM.atomically:
      completedTransfers
        .get(key)
        .flatMap:
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
