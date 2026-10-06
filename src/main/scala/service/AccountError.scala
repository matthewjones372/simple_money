package service

sealed trait AccountError

object AccountError:
  case object AccountDoesNotExist extends AccountError

  case object AccountHasInsufficientFunds extends AccountError

  case object AccountAlreadyExists extends AccountError

  case object CannotTransferToSameAccount extends AccountError

  case object CannotTransferToAccountWithDifferentCurrency extends AccountError

  case object TransferAmountNotPositive extends AccountError

  case object CannotOpenAccountWithNegativeBalance extends AccountError

  case object AmountHasTooManyDecimalPlaces extends AccountError

  case object AmountTooLarge extends AccountError

  case object UnknownCurrency extends AccountError

  case object IdempotencyKeyReusedForDifferentTransfer extends AccountError

  case object IdempotencyKeyIsBlank extends AccountError

  case object InvalidPageSize extends AccountError
