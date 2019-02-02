package infrastructure.actors

import domain.model.{AccountNumber, Currency, CurrencyAccount, CurrencyAmount}
import infrastructure.actors.AccountActor.{PostNewAccount, TransferBetweenAccounts}
import io.circe.generic.extras.Configuration
import io.circe.generic.extras.semiauto.{deriveDecoder, deriveEncoder}
import io.circe.{Decoder, Encoder}

trait JsonCodecs {
  implicit val configCirce: Configuration = Configuration.default.withDiscriminator("type")

  implicit val accountNumberEncoder: Encoder[AccountNumber] = deriveEncoder[AccountNumber]
  implicit val accountNumberDecoder: Decoder[AccountNumber] = deriveDecoder[AccountNumber]

  implicit val currencyAmountEncoder: Encoder[CurrencyAmount] = deriveEncoder[CurrencyAmount]

  implicit val currencyAmountDecoder: Decoder[CurrencyAmount] = deriveDecoder[CurrencyAmount]

  implicit val accountEncoder: Encoder[CurrencyAccount] =deriveEncoder[CurrencyAccount]
  implicit val accountDecoder: Decoder[CurrencyAccount] = deriveDecoder[CurrencyAccount]

  implicit val currencyEncoder: Encoder[Currency] = deriveEncoder[Currency]
  implicit val currencyDecoder: Decoder[Currency] = deriveDecoder[Currency]

  implicit val transferBetweenAccountsEncoder: Encoder[TransferBetweenAccounts] = deriveEncoder[TransferBetweenAccounts]
  implicit val transferBetweenAccountsDecoder: Decoder[TransferBetweenAccounts] = deriveDecoder[TransferBetweenAccounts]

  implicit val newAccountEncoder: Encoder[PostNewAccount] = deriveEncoder[PostNewAccount]
  implicit val newAccountDecoder: Decoder[PostNewAccount] = deriveDecoder[PostNewAccount]

}
