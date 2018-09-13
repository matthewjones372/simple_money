package infrastructure.actors

import infrastructure.actors.AccountActor.{
  HttpResponse, PostNewAccount, transferBetweenAccounts}
import io.circe.generic.extras.Configuration
import io.circe.generic.extras.semiauto.{deriveDecoder, deriveEncoder}
import io.circe.{Decoder, Encoder}
import domain.model.{Currency, CurrencyAccount}

trait JsonCodecs {
  implicit val configCirce: Configuration = Configuration.default.withDiscriminator("type")

  implicit val accountEncoder: Encoder[CurrencyAccount] = deriveEncoder[CurrencyAccount]
  implicit val accountDecoder: Decoder[CurrencyAccount] = deriveDecoder[CurrencyAccount]

  implicit val currencyEncoder: Encoder[Currency] = deriveEncoder[Currency]
  implicit val currencyDecoder: Decoder[Currency] = deriveDecoder[Currency]

  implicit val transferBetweenAccountsEncoder: Encoder[transferBetweenAccounts] =
    deriveEncoder[transferBetweenAccounts]
  implicit val transferBetweenAccountsDecoder: Decoder[transferBetweenAccounts] =
    deriveDecoder[transferBetweenAccounts]

  implicit val newAccountEncoder: Encoder[PostNewAccount] =
    deriveEncoder[PostNewAccount]

  implicit val newAccountDecoder: Decoder[PostNewAccount] =
    deriveDecoder[PostNewAccount]

  implicit val actionPerformedEncoder: Encoder[HttpResponse] =
    deriveEncoder[HttpResponse]
  implicit val actionPerformedDecoder: Decoder[HttpResponse] =
    deriveDecoder[HttpResponse]
}
