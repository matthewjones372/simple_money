package domain

import java.util.UUID

/**
 * An account's identifier in URLs, so that account numbers, which are personal
 * data, stay out of URLs and the access logs that record them
 */
final case class AccountId(value: UUID) extends AnyVal
