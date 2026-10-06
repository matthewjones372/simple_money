package domain

/**
 * One page of accounts, in account number order; `next` is the cursor for the
 * following page, if there is one: the id of the last account on this page
 */
final case class AccountPage(accounts: Seq[CurrencyAccount], next: Option[AccountId])
