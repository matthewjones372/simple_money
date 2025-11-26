package service

sealed trait TransferServiceErrors

object TransferServiceErrors {
  case object AccountDoesNotExist extends TransferServiceErrors

  case object AccountHasInsufficientFunds extends TransferServiceErrors

  case object AccountAlreadyExists extends TransferServiceErrors

  case object FailedToUpdateAccount extends TransferServiceErrors

  case object CannotTransferToSameAccount extends TransferServiceErrors

  case object CannotTransferToAccountWithDifferentCurrency extends TransferServiceErrors

  case object CannotTransferNegativeAmount extends TransferServiceErrors
}
