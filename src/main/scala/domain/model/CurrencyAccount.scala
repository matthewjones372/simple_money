package domain.model

final case class CurrencyAccount(
    accountNumber: String,
    balance: Double,
    currency: Currency
)
