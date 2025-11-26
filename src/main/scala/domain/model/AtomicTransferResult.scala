package domain.model

final case class AtomicTransferResult(
  fromBefore: CurrencyAccount,
  toBefore: CurrencyAccount,
  fromAfter: CurrencyAccount,
  toAfter: CurrencyAccount
)
