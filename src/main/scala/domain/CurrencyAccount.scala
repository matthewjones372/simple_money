package domain

import java.util.Currency

final case class CurrencyAccount(
  accountNumber: AccountNumber,
  balance: CurrencyAmount,
  currency: Currency
)

final case class AccountNumber(value: String) extends AnyVal {
  def !=(that: AccountNumber): Boolean = this.value != that.value

  /**
   * The last four characters, for logs, which should not hold whole account
   * numbers
   */
  def masked: String = "****" + value.filterNot(_.isWhitespace).takeRight(4)

  // Masked so that logging an account, or anything holding one, does not leak the number
  override def toString: String = s"AccountNumber($masked)"
}

final case class CurrencyAmount(value: BigDecimal) {
  def +(that: CurrencyAmount): CurrencyAmount = CurrencyAmount(this.value + that.value)

  def -(that: CurrencyAmount): CurrencyAmount = CurrencyAmount(this.value - that.value)

  def >=(that: CurrencyAmount): Boolean = this.value >= that.value

}

/**
 * One page of accounts, in account number order; `next` is the cursor for the
 * following page, if there is one
 */
final case class AccountPage(accounts: Seq[CurrencyAccount], next: Option[AccountNumber])
