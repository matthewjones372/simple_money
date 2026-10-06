package domain

import java.util.Currency

/**
 * An amount of a currency. Adding, subtracting and comparing are only defined
 * between amounts of the same currency; the transfer rules check the currencies
 * first, so a mismatch here is a bug and throws.
 *
 * Adding and subtracting are exact. Scala's BigDecimal arithmetic rounds to 34
 * significant digits, which would let a transfer into a large balance lose the
 * amount moved, so they use Java's add and subtract, which do not round.
 */
final case class Money(amount: BigDecimal, currency: Currency):
  def +(that: Money): Money = Money(BigDecimal(amount.bigDecimal.add(sameCurrency(that).amount.bigDecimal)), currency)

  def -(that: Money): Money =
    Money(BigDecimal(amount.bigDecimal.subtract(sameCurrency(that).amount.bigDecimal)), currency)

  def >=(that: Money): Boolean = amount >= sameCurrency(that).amount

  def isNegative: Boolean = amount < 0

  /** Whether the amount is below the largest amount the service accepts */
  def isWithinMaximum: Boolean = Money.isWithinMaximum(amount)

  /**
   * Whether the amount is a whole number of the currency's minor unit, so 0.01
   * GBP is and 0.001 GBP is not. Currencies without a minor unit, such as XAU,
   * report -1 and accept any amount.
   */
  def fitsMinorUnit: Boolean =
    val fractionDigits = currency.getDefaultFractionDigits
    fractionDigits < 0 || amount.bigDecimal.stripTrailingZeros.scale <= fractionDigits

  private def sameCurrency(that: Money): Money =
    require(currency == that.currency, s"Cannot combine $currency with ${that.currency}")
    that

object Money:

  /**
   * Opening balances and transfer amounts must have at most this many digits
   * before the decimal point, so be less than 10^15
   */
  val maxIntegerDigits: Int = 15

  /**
   * Counts the digits before the decimal point rather than comparing with
   * 10^15, since comparing makes BigDecimal rescale, and an amount such as
   * 1e999999999 overflows when rescaled
   */
  def isWithinMaximum(amount: BigDecimal): Boolean =
    amount.signum == 0 || amount.precision - amount.scale <= maxIntegerDigits
