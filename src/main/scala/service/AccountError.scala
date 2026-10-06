package service

/**
 * Why an account operation failed, in four kinds. Each service method's error
 * type names the kinds it can fail with, so the HTTP layer can declare exactly
 * the statuses an endpoint can return.
 */
sealed trait AccountError

object AccountError:

  /** The request is invalid in itself */
  sealed trait Invalid extends AccountError

  /** Something the request names does not exist */
  sealed trait Missing extends AccountError

  /** The request conflicts with something already there */
  sealed trait Conflicting extends AccountError

  /** A valid request that the accounts, as they stand, cannot carry out */
  sealed trait Refused extends AccountError

  case object AccountDoesNotExist extends Missing

  case object AccountHasInsufficientFunds extends Refused

  case object AccountAlreadyExists extends Conflicting

  case object CannotTransferToSameAccount extends Invalid

  case object CannotTransferToAccountWithDifferentCurrency extends Refused

  case object TransferAmountNotPositive extends Invalid

  case object CannotOpenAccountWithNegativeBalance extends Invalid

  case object AmountHasTooManyDecimalPlaces extends Invalid

  case object AmountTooLarge extends Invalid

  case object UnknownCurrency extends Invalid

  case object InvalidAccountNumber extends Invalid

  case object IdempotencyKeyReusedForDifferentTransfer extends Conflicting

  case object IdempotencyKeyIsBlank extends Invalid

  case object InvalidPageSize extends Invalid

  case object InvalidCursor extends Invalid
