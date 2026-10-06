package domain

/**
 * One page of accounts, in account number order; `next` is the cursor for the
 * following page, if there is one
 */
final case class AccountPage(accounts: Seq[CurrencyAccount], next: Option[AccountNumber])
