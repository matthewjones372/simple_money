package model

final case class CurrencyAccount(
    iban: String,
    balance: Double,
    currency: Currency
)
