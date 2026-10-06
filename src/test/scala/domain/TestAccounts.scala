package domain

import java.nio.charset.StandardCharsets.UTF_8
import java.util.UUID

object TestAccounts:

  /** An account whose id is derived from its number, so tests can predict it */
  def accountOf(accountNumber: AccountNumber, balance: Money): CurrencyAccount =
    CurrencyAccount(AccountId(UUID.nameUUIDFromBytes(accountNumber.value.getBytes(UTF_8))), accountNumber, balance)
