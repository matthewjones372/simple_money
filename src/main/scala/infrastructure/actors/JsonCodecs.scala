package infrastructure.actors

import java.util.Currency

import domain.model.{AccountNumber, CurrencyAccount, CurrencyAmount}
import infrastructure.actors.AccountActor.{PostNewAccount, TransferBetweenAccounts}
import io.circe.generic.semiauto.{deriveDecoder, deriveEncoder}
import io.circe.{Decoder, Encoder}
import sttp.tapir.Schema

import scala.util.Try

trait JsonCodecs {
  implicit val accountNumberEncoder: Encoder[AccountNumber] = Encoder.encodeString.contramap(_.value)
  implicit val accountNumberDecoder: Decoder[AccountNumber] = Decoder.decodeString.map(AccountNumber.fromString)

  implicit val accountNumberSchema: Schema[AccountNumber] =
    Schema.string.map[AccountNumber]((value: String) => Some(AccountNumber.fromString(value)))
      ((accountNumber: AccountNumber) => accountNumber.value)

  implicit val currencyAmountEncoder: Encoder[CurrencyAmount] = Encoder.encodeBigDecimal.contramap(_.value)

  implicit val currencyAmountDecoder: Decoder[CurrencyAmount] =
    Decoder.decodeBigDecimal.map(CurrencyAmount.fromBigDecimal)

  implicit val currencyEncoder: Encoder[Currency] = Encoder.encodeString.contramap(_.getCurrencyCode)
  implicit val currencyDecoder: Decoder[Currency] = Decoder.decodeString.emap { code =>
    Try(Currency.getInstance(code)).toEither.left.map(_ => s"Unknown currency $code")
  }

  implicit val currencySchema: Schema[Currency] =
    Schema.string.map[Currency]((code: String) => Try(Currency.getInstance(code)).toOption)(_.getCurrencyCode)

  implicit val accountEncoder: Encoder[CurrencyAccount] = deriveEncoder[CurrencyAccount]
  implicit val accountDecoder: Decoder[CurrencyAccount] = deriveDecoder[CurrencyAccount]

  implicit val transferBetweenAccountsEncoder: Encoder[TransferBetweenAccounts] = deriveEncoder[TransferBetweenAccounts]
  implicit val transferBetweenAccountsDecoder: Decoder[TransferBetweenAccounts] = deriveDecoder[TransferBetweenAccounts]

  implicit val newAccountEncoder: Encoder[PostNewAccount] = deriveEncoder[PostNewAccount]
  implicit val newAccountDecoder: Decoder[PostNewAccount] = deriveDecoder[PostNewAccount]

}
