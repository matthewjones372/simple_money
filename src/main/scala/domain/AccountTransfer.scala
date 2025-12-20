package domain

final case class AccountTransfer(
  fromBefore: CurrencyAccount,
  toBefore: CurrencyAccount,
  fromAfter: CurrencyAccount,
  toAfter: CurrencyAccount
)
