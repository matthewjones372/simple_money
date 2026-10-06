package domain

final case class AccountNumber(value: String) extends AnyVal:

  /**
   * Not blank, no longer than 64 characters, without spaces at either end,
   * which would make "A " a different account from "A", and without control
   * characters. Spaces inside, as IBANs are often written, are allowed.
   */
  def isValid: Boolean =
    value.nonEmpty && value.length <= AccountNumber.maxLength && value.strip == value &&
      !value.exists(Character.isISOControl)

  /**
   * The last four characters, for logs, which should not hold whole account
   * numbers
   */
  def masked: String = "****" + value.filterNot(_.isWhitespace).takeRight(4)

  // Masked so that logging an account, or anything holding one, does not leak the number
  override def toString: String = s"AccountNumber($masked)"

object AccountNumber:
  val maxLength: Int = 64
