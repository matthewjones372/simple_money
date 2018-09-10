package infrastructure.actors

import infrastructure.actors.AccountActor.{ ActionPerformed, transferBetweenAccounts }
import io.circe.generic.extras.Configuration
import io.circe.generic.extras.semiauto.{ deriveDecoder, deriveEncoder }
import io.circe.{ Decoder, Encoder }
import model.{ Currency, CurrencyAccount }

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

  implicit val actionPerformedEncoder: Encoder[ActionPerformed] = deriveEncoder[ActionPerformed]
  implicit val actionPerformedDecoder: Decoder[ActionPerformed] = deriveDecoder[ActionPerformed]
}
