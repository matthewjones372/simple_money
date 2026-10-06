package domain

import java.util.Currency

final case class CurrencyAccount(accountNumber: AccountNumber, balance: Money) {
  def currency: Currency = balance.currency
}

final case class AccountNumber(value: String) extends AnyVal {

  /**
   * The last four characters, for logs, which should not hold whole account
   * numbers
   */
  def masked: String = "****" + value.filterNot(_.isWhitespace).takeRight(4)

  // Masked so that logging an account, or anything holding one, does not leak the number
  override def toString: String = s"AccountNumber($masked)"
}

/**
 * An amount of a currency. Adding, subtracting and comparing are only defined
 * between amounts of the same currency; the transfer rules check the currencies
 * first, so a mismatch here is a bug and throws.
 */
final case class Money(amount: BigDecimal, currency: Currency) {
  def +(that: Money): Money = Money(amount + sameCurrency(that).amount, currency)

  def -(that: Money): Money = Money(amount - sameCurrency(that).amount, currency)

  def >=(that: Money): Boolean = amount >= sameCurrency(that).amount

  def isNegative: Boolean = amount < 0

  /**
   * Whether the amount is a whole number of the currency's minor unit, so 0.01
   * GBP is and 0.001 GBP is not. Currencies without a minor unit, such as XAU,
   * report -1 and accept any amount.
   */
  def fitsMinorUnit: Boolean = {
    val fractionDigits = currency.getDefaultFractionDigits
    fractionDigits < 0 || amount.bigDecimal.stripTrailingZeros.scale <= fractionDigits
  }

  private def sameCurrency(that: Money): Money = {
    require(currency == that.currency, s"Cannot combine $currency with ${that.currency}")
    that
  }
}

/**
 * One page of accounts, in account number order; `next` is the cursor for the
 * following page, if there is one
 */
final case class AccountPage(accounts: Seq[CurrencyAccount], next: Option[AccountNumber])
