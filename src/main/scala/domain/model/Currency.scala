package domain.model

sealed trait Currency

object Currency {

  case object GBP extends Currency

  case object USD extends Currency

  case object EUR extends Currency
}
