package service

import cats.implicits._
import domain.algerbra.{AccountGatewayAlg, LoggingAlg}
import domain.model.{Currency, CurrencyAccount}
import org.scalatest.{FreeSpec, Matchers}

import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer
import scala.util.{Success, Try}

class CurrencyAccountTransferServiceUnitTest extends FreeSpec with Matchers {

  "TransferService with a successful gateway" - {
    "listAllAccount should" - {
      "return a seq of currency accounts" in new TestSuite {
        val results: Seq[CurrencyAccount] = transferService.listAllAccounts.get
        results.size shouldBe 4
        results.filter(_.accountNumber == accountWithPositiveFunds).head.accountNumber shouldBe positiveAccount.accountNumber
      }

      "transferBetweenAccounts should " - {
        "update both accounts if sufficient funds are present" in new TestSuite {
          transferService.accountTransfer(accountWithPositiveFunds, accountWithGBP, 5)

          val updatedAccount1: CurrencyAccount =
            gateway.getAccount(accountWithPositiveFunds).get.right.get

          val updatedAccount2: CurrencyAccount =
            gateway.getAccount(accountWithGBP).get.right.get

          updatedAccount1.balance shouldBe 95.0
          updatedAccount2.balance shouldBe 205.0

          logAudit.exists(f => f.contains("100 GBP to 95 GBP")) shouldBe true
          logAudit.exists(f => f.contains("200 GBP to 205 GBP")) shouldBe true
        }

        "allow multiple payments" in new TestSuite {

          //accuntWithPositiveFunds starts with 100
          //accountWithGBP starts with 200
          transferService.accountTransfer(accountWithPositiveFunds, accountWithGBP, 10)
          transferService.accountTransfer(accountWithPositiveFunds, accountWithGBP, 10)
          transferService.accountTransfer(accountWithPositiveFunds, accountWithGBP, 10)
          transferService.accountTransfer(accountWithPositiveFunds, accountWithGBP, 10)
          transferService.accountTransfer(accountWithPositiveFunds, accountWithGBP, 10)

          val updatedAccount1: CurrencyAccount =
            gateway.getAccount(accountWithPositiveFunds).get.right.get

          val updatedAccount2: CurrencyAccount =
            gateway.getAccount(accountWithGBP).get.right.get

          updatedAccount1.balance shouldBe 50
          updatedAccount2.balance shouldBe 250

          logAudit.exists(f => f.contains("100 GBP to 90 GBP")) shouldBe true
          logAudit.exists(f => f.contains("80 GBP to 70 GBP")) shouldBe true
        }

        "not transfer funds when an account does not exist" in new TestSuite {
          val nonExistingAccount = "SOME_NON_EXISTING_ACCOUNT"
          val gatewayError: TransferServiceErrors =
            transferService.accountTransfer(nonExistingAccount, accountWithGBP, 2).get.left.get

          gatewayError === AccountDoesNotExist
        }

        "transferBetweenAccounts should not transfer funds when there is no account to transfer to" in new TestSuite {
          val nonExistingAccount = "SOME_NON_EXISTING_ACCOUNT"
          val gatewayError: TransferServiceErrors =
            transferService
              .accountTransfer(accountWithPositiveFunds, nonExistingAccount, 2)
              .get
              .left
              .get
          gatewayError === AccountDoesNotExist
          gateway.getAccount(accountWithPositiveFunds).get.right.get.balance shouldBe 100.0
        }

        "not transfer when funds are not sufficient" in new TestSuite {
          val result: Either[TransferServiceErrors, Unit] = transferService
            .accountTransfer(accountWithNegativeFunds, accountWithPositiveFunds, 200)
            .get

          gateway.getAccount(accountWithNegativeFunds).get.right.get.balance shouldBe -19.99
          gateway.getAccount(accountWithPositiveFunds).get.right.get.balance shouldBe 100
          result.left.get === AccountHasInsufficientFunds
        }

        "not be able to transfer between the same account" in new TestSuite {
          val result: Either[TransferServiceErrors, Unit] = transferService
            .accountTransfer(accountWithPositiveFunds, accountWithPositiveFunds, 30)
            .get

          result.left.get === CannotTransferToSameAccount
          gateway.getAccount(accountWithPositiveFunds).get.right.get.balance shouldBe 100
        }

        "not be able to transfer between different currencies" in new TestSuite {
          val result: Either[TransferServiceErrors, Unit] =
            transferService.accountTransfer(accountWithGBP, accountWithEur, 30).get

          result.left.get === CannotTransferToAccountWithDifferentCurrency
        }
      }
    }
  }

  private class TestSuite() extends TransferServiceErrors {

    val logAudit: ArrayBuffer[String] = ArrayBuffer.empty[String]

    class testLogger extends LoggingAlg[Try] {
      override def info(msg: String): Try[Unit] = Success(logAudit.append(msg))

      override def warn(msg: String): Try[Unit] = Success(logAudit.append(msg))

      override def error(msg: String, ex: Throwable): Try[Unit] = Success(logAudit.append(s"$msg: $ex"))
    }

    lazy val gateway: AccountGatewayAlg[Try] = testGateway(accounts)

    lazy val transferService = new AccountTransferService[Try](gateway, new testLogger)

    val accountWithPositiveFunds = "ACCOUNT_WITH_POSITIVE"
    val positiveAccount = CurrencyAccount(accountWithPositiveFunds, 100, Currency.GBP)

    val accountWithGBP = "ACCOUNT_WITH_GBP"
    val GBPAccount = CurrencyAccount(accountWithGBP, 200, Currency.GBP)

    val accountWithEur = "ACCOUNT_WITH_EUR"
    val EURAccount = CurrencyAccount(accountWithEur, 503.4, Currency.EUR)

    val accountWithNegativeFunds = "ACCOUNT_WITH_NEGATIVE_FUNDS"
    val negativeAccount =
      CurrencyAccount(accountWithNegativeFunds, -19.99, Currency.GBP)

    val accounts = mutable.Map(
      accountWithPositiveFunds -> positiveAccount,
      accountWithGBP -> GBPAccount,
      accountWithEur -> EURAccount,
      accountWithNegativeFunds -> negativeAccount
    )

    def testGateway(accounts: mutable.Map[String, CurrencyAccount]): AccountGatewayAlg[Try] = new AccountGatewayAlg[Try] {
        override def getAllAccounts: Try[Seq[CurrencyAccount]] = Success(accounts.values.toVector)

        override def getAccount(iban: String): Try[Either[TransferServiceErrors, CurrencyAccount]] =
          Success(accounts.get(iban).map(Right(_)).getOrElse(Left(AccountDoesNotExist)))

        override def updateAccount(account: CurrencyAccount): Try[Either[TransferServiceErrors, Unit]] =
          Success(Right(accounts.update(account.accountNumber, account)))

      override def postAccount(account: CurrencyAccount): Try[Either[TransferServiceErrors, Unit]] = ???
    }
  }

}
