package domain

final case class AccountNumber(value: String) extends AnyVal:

  /**
   * The last four characters, for logs, which should not hold whole account
   * numbers
   */
  def masked: String = "****" + value.filterNot(_.isWhitespace).takeRight(4)

  // Masked so that logging an account, or anything holding one, does not leak the number
  override def toString: String = s"AccountNumber($masked)"
