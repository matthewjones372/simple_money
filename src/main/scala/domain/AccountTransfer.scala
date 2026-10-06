package domain

final case class AccountTransfer(
  fromBefore: CurrencyAccount,
  toBefore: CurrencyAccount,
  fromAfter: CurrencyAccount,
  toAfter: CurrencyAccount
)

final case class IdempotencyKey(value: String) extends AnyVal

final case class TransferInstruction(
  fromAccountNumber: AccountNumber,
  toAccountNumber: AccountNumber,
  amount: CurrencyAmount
)

enum TransferOutcome:
  case Applied(transfer: AccountTransfer)
  case Replayed(transfer: AccountTransfer)
