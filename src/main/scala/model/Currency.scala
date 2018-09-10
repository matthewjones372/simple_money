package model

sealed trait Currency {
  def symbol: String
}

object Currency {

  case object GBP extends Currency {
    val symbol: String = "£"
  }

  case object USD extends Currency {
    val symbol: String = "$"
  }

  case object EUR extends Currency {
    val symbol: String = "€"
  }

}
