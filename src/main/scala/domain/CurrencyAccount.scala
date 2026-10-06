package domain

import java.util.Currency

final case class CurrencyAccount(id: AccountId, accountNumber: AccountNumber, balance: Money):
  def currency: Currency = balance.currency
