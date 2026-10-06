package domain

final case class AccountTransfer(
  fromBefore: CurrencyAccount,
  toBefore: CurrencyAccount,
  fromAfter: CurrencyAccount,
  toAfter: CurrencyAccount
)

final case class TransferInstruction(
  fromAccountNumber: AccountNumber,
  toAccountNumber: AccountNumber,
  amount: BigDecimal
)

enum TransferOutcome:
  case Applied(transfer: AccountTransfer)
  case Replayed(transfer: AccountTransfer)
