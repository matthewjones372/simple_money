package domain

import java.util.Currency

final case class CurrencyAccount(
  accountNumber: AccountNumber,
  balance: CurrencyAmount,
  currency: Currency
)

final case class AccountNumber(value: String) extends AnyVal {
  def !=(that: AccountNumber): Boolean = this.value != that.value
}

final case class CurrencyAmount(value: BigDecimal) {
  def +(that: CurrencyAmount): CurrencyAmount = CurrencyAmount(this.value + that.value)

  def -(that: CurrencyAmount): CurrencyAmount = CurrencyAmount(this.value - that.value)

  def >=(that: CurrencyAmount): Boolean = this.value >= that.value

  def >(that: CurrencyAmount): Boolean = this.value > that.value
}

object CurrencyAmount {
  def fromBigDecimal(value: BigDecimal): CurrencyAmount = CurrencyAmount(value)
}
