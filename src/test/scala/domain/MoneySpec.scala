package domain

import java.util.Currency
import zio.*
import zio.test.*

object MoneySpec extends ZIOSpecDefault:

  val gbp: Currency = Currency.getInstance("GBP")
  val eur: Currency = Currency.getInstance("EUR")
  val jpy: Currency = Currency.getInstance("JPY")
  val xau: Currency = Currency.getInstance("XAU")

  def spec = suite("MoneySpec")(
    test("adds, subtracts and compares amounts of one currency") {
      val sum           = Money(10, gbp) + Money(2.50, gbp)
      val difference    = Money(10, gbp) - Money(2.50, gbp)
      val equalCovers   = Money(10, gbp) >= Money(10, gbp)
      val smallerCovers = Money(9.99, gbp) >= Money(10, gbp)
      assertTrue(
        sum == Money(12.50, gbp),
        difference == Money(7.50, gbp),
        equalCovers,
        !smallerCovers
      )
    },
    test("adds and subtracts exactly, however many digits the result has") {
      val large      = Money(BigDecimal("1e40"), gbp)
      val penny      = Money(BigDecimal("0.01"), gbp)
      val sum        = large + penny
      val difference = (large + penny) - large
      assertTrue(
        sum.amount == BigDecimal("10000000000000000000000000000000000000000.01"),
        difference.amount == BigDecimal("0.01")
      )
    },
    test("knows whether an amount is below the maximum") {
      assertTrue(
        Money(BigDecimal("999999999999999.99"), gbp).isWithinMaximum,
        !Money(BigDecimal("1e15"), gbp).isWithinMaximum,
        !Money(BigDecimal("-1e15"), gbp).isWithinMaximum,
        !Money(BigDecimal("1e999999999"), gbp).isWithinMaximum,
        Money(BigDecimal("1e-999999999"), gbp).isWithinMaximum,
        Money(BigDecimal(0), gbp).isWithinMaximum,
        Money(BigDecimal("0E+20"), gbp).isWithinMaximum
      )
    },
    test("refuses to combine amounts of different currencies") {
      for
        sum        <- ZIO.attempt(Money(10, gbp) + Money(1, eur)).either
        difference <- ZIO.attempt(Money(10, gbp) - Money(1, eur)).either
        comparison <- ZIO.attempt(Money(10, gbp) >= Money(1, eur)).either
      yield assertTrue(
        sum.left.exists(_.isInstanceOf[IllegalArgumentException]),
        difference.isLeft,
        comparison.isLeft
      )
    },
    test("knows whether an amount fits its currency's minor unit") {
      assertTrue(
        Money(BigDecimal("0.01"), gbp).fitsMinorUnit,
        Money(BigDecimal("50.000"), gbp).fitsMinorUnit,
        !Money(BigDecimal("0.001"), gbp).fitsMinorUnit,
        Money(BigDecimal("100"), jpy).fitsMinorUnit,
        !Money(BigDecimal("100.5"), jpy).fitsMinorUnit,
        Money(BigDecimal("0.000001"), xau).fitsMinorUnit
      )
    },
    test("knows whether an amount is negative") {
      assertTrue(Money(-0.01, gbp).isNegative, !Money(0, gbp).isNegative)
    }
  )
