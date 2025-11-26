package service

import java.util.Currency

import cats.implicits._

import domain.algebra.{AccountGatewayAlg, LoggingAlg}
import domain.model.{AccountNumber, CurrencyAccount, CurrencyAmount}
import org.scalatest.matchers.should.Matchers
import org.scalatest.freespec.AnyFreeSpec
import service.TransferServiceErrors._

import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer
import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.util.{Success, Try}

class CurrencyAccountTransferServiceUnitTest extends AnyFreeSpec with Matchers {

  "TransferService with a successful gateway" - {
    "listAllAccount should" - {
      "return a seq of currency accounts" in new TestSuite {
        val results: Seq[CurrencyAccount] = transferService.listAllAccounts.get
        results.size shouldBe 4
        results
          .filter(_.accountNumber === accountWithPositiveFunds)
          .head
          .accountNumber shouldBe positiveAccount.accountNumber
      }

      "transferBetweenAccounts should " - {
        "update both accounts if sufficient funds are present" in new TestSuite {
          transferService.accountTransfer(accountWithPositiveFunds, accountWithGBP, CurrencyAmount.fromBigDecimal(5))

          val updatedAccount1: CurrencyAccount =
            gateway.getAccount(accountWithPositiveFunds).get.toOption.get

          val updatedAccount2: CurrencyAccount =
            gateway.getAccount(accountWithGBP).get.toOption.get

          updatedAccount1.balance shouldBe CurrencyAmount.fromBigDecimal(95)
          updatedAccount2.balance shouldBe CurrencyAmount.fromBigDecimal(205)

          logAudit.exists(f => f.contains("100 GBP to 95 GBP")) shouldBe true
          logAudit.exists(f => f.contains("200 GBP to 205 GBP")) shouldBe true
        }

        "allow multiple payments" in new TestSuite {

          transferService.accountTransfer(accountWithPositiveFunds, accountWithGBP, CurrencyAmount(10))
          transferService.accountTransfer(accountWithPositiveFunds, accountWithGBP, CurrencyAmount(10))
          transferService.accountTransfer(accountWithPositiveFunds, accountWithGBP, CurrencyAmount(10))
          transferService.accountTransfer(accountWithPositiveFunds, accountWithGBP, CurrencyAmount(10))
          transferService.accountTransfer(accountWithPositiveFunds, accountWithGBP, CurrencyAmount(10))

          val updatedAccount1: CurrencyAccount =
            gateway.getAccount(accountWithPositiveFunds).get.toOption.get

          val updatedAccount2: CurrencyAccount =
            gateway.getAccount(accountWithGBP).get.toOption.get

          updatedAccount1.balance shouldBe CurrencyAmount(50)
          updatedAccount2.balance shouldBe CurrencyAmount(250)

          logAudit.exists(f => f.contains("100 GBP to 90 GBP")) shouldBe true
          logAudit.exists(f => f.contains("80 GBP to 70 GBP")) shouldBe true
        }

        "not transfer funds when an account does not exist" in new TestSuite {
          val nonExistingAccount = AccountNumber("SOME_NON_EXISTING_ACCOUNT")
          val gatewayError: TransferServiceErrors =
            transferService
              .accountTransfer(nonExistingAccount, accountWithGBP, CurrencyAmount(2))
              .get
              .swap
              .getOrElse(fail("Expected transfer failure"))

          gatewayError.toString shouldBe AccountDoesNotExist.toString
        }

        "not transfer funds when a negative transfer is requested" in new TestSuite {

          val gatewayError: TransferServiceErrors =
            transferService
              .accountTransfer(accountWithGBP, accountWithPositiveFunds, CurrencyAmount(-100))
              .get
              .swap
              .getOrElse(fail("Expected transfer failure"))

          gatewayError.toString shouldBe CannotTransferNegativeAmount.toString
        }

        "transferBetweenAccounts should not transfer funds when there is no account to transfer to" in new TestSuite {
          val nonExistingAccount = AccountNumber("SOME_NON_EXISTING_ACCOUNT")
          val gatewayError: TransferServiceErrors =
            transferService
              .accountTransfer(accountWithPositiveFunds, nonExistingAccount, CurrencyAmount(2))
              .get
              .swap
              .getOrElse(fail("Expected transfer failure"))
          gatewayError.toString shouldBe AccountDoesNotExist.toString
          gateway.getAccount(accountWithPositiveFunds).get.toOption.get.balance shouldBe CurrencyAmount(100.0)
        }

        "not transfer when funds are not sufficient" in new TestSuite {
          val result: Either[TransferServiceErrors, Unit] = transferService
            .accountTransfer(accountWithNegativeFunds, accountWithPositiveFunds, CurrencyAmount(200))
            .get

          gateway.getAccount(accountWithNegativeFunds).get.toOption.get.balance shouldBe CurrencyAmount(-19.99)
          gateway.getAccount(accountWithPositiveFunds).get.toOption.get.balance shouldBe CurrencyAmount(100)
          result.swap
            .getOrElse(fail("Expected transfer failure"))
            .toString shouldBe AccountHasInsufficientFunds.toString
        }

        "not be able to transfer between the same account" in new TestSuite {
          val result: Either[TransferServiceErrors, Unit] = transferService
            .accountTransfer(accountWithPositiveFunds, accountWithPositiveFunds, CurrencyAmount(30))
            .get

          result.swap
            .getOrElse(fail("Expected transfer failure"))
            .toString shouldBe CannotTransferToSameAccount.toString
          gateway.getAccount(accountWithPositiveFunds).get.toOption.get.balance shouldBe CurrencyAmount(100)
        }

        "not be able to transfer between different currencies" in new TestSuite {
          val result: Either[TransferServiceErrors, Unit] =
            transferService.accountTransfer(accountWithGBP, accountWithEur, CurrencyAmount(30)).get

          result.swap
            .getOrElse(fail("Expected transfer failure"))
            .toString shouldBe CannotTransferToAccountWithDifferentCurrency.toString
        }
      }
    }
  }

  "TransferService with atomic datastore" - {
    "complete concurrent transfers without losing funds" in {
      implicit val ec: ExecutionContext = ExecutionContext.global
      val gateway                       = new infrastructure.dataStores.InMemoryEvalDataStore
      val logger = new LoggingAlg[Try] {
        override def info(msg: String): Try[Unit]                 = Success(())
        override def warn(msg: String): Try[Unit]                 = Success(())
        override def error(msg: String, ex: Throwable): Try[Unit] = Success(())
      }
      val transferService = new AccountTransferService[EvalWrapper](
        new EvalWrapperGateway(gateway),
        new EvalWrapperLogger(logger)
      )

      val fromAccount = AccountNumber("CONCURRENT_FROM")
      val toAccount   = AccountNumber("CONCURRENT_TO")

      val gbp = Currency.getInstance("GBP")
      gateway.postAccount(CurrencyAccount(fromAccount, CurrencyAmount(500), gbp)).value
      gateway.postAccount(CurrencyAccount(toAccount, CurrencyAmount(0), gbp)).value

      val transfers = (1 to 100).map { _ =>
        Future(transferService.accountTransfer(fromAccount, toAccount, CurrencyAmount(1)).value)
      }

      Await.result(Future.sequence(transfers), 5.seconds)

      gateway.getAccount(fromAccount).value.toOption.get.balance shouldBe CurrencyAmount(400)
      gateway.getAccount(toAccount).value.toOption.get.balance shouldBe CurrencyAmount(100)
    }
  }

  private class TestSuite() {

    val logAudit: ArrayBuffer[String] = ArrayBuffer.empty[String]

    private val gbp = Currency.getInstance("GBP")
    private val eur = Currency.getInstance("EUR")

    class testLogger extends LoggingAlg[Try] {
      override def info(msg: String): Try[Unit] =
        Success(logAudit.append(msg))

      override def warn(msg: String): Try[Unit] =
        Success(logAudit.append(msg))

      override def error(msg: String, ex: Throwable): Try[Unit] =
        Success(logAudit.append(s"$msg: $ex"))
    }

    lazy val gateway: AccountGatewayAlg[Try] = testGateway(accounts)

    lazy val transferService =
      new AccountTransferService[Try](gateway, new testLogger)

    val accountWithPositiveFunds = AccountNumber("ACCOUNT_WITH_POSITIVE")
    val positiveAccount =
      CurrencyAccount(accountWithPositiveFunds, CurrencyAmount(100), gbp)

    val accountWithGBP = AccountNumber("ACCOUNT_WITH_GBP")
    val GBPAccount     = CurrencyAccount(accountWithGBP, CurrencyAmount(200), gbp)

    val accountWithEur = AccountNumber("ACCOUNT_WITH_EUR")
    val EURAccount     = CurrencyAccount(accountWithEur, CurrencyAmount(503.4), eur)

    val accountWithNegativeFunds = AccountNumber("ACCOUNT_WITH_NEGATIVE_FUNDS")
    val negativeAccount =
      CurrencyAccount(accountWithNegativeFunds, CurrencyAmount(-19.99), gbp)

    val accounts: mutable.Map[String, CurrencyAccount] = mutable.Map[String, CurrencyAccount](
      accountWithPositiveFunds.value -> positiveAccount,
      accountWithGBP.value           -> GBPAccount,
      accountWithEur.value           -> EURAccount,
      accountWithNegativeFunds.value -> negativeAccount
    )

    def testGateway(accounts: mutable.Map[String, CurrencyAccount]): AccountGatewayAlg[Try] =
      new AccountGatewayAlg[Try] {
        override def getAllAccounts: Try[Seq[CurrencyAccount]] =
          Success(accounts.values.toVector)

        override def getAccount(accountNumber: AccountNumber): Try[Either[TransferServiceErrors, CurrencyAccount]] =
          Success(accounts.get(accountNumber.value).map(Right(_)).getOrElse(Left(AccountDoesNotExist)))

        override def updateAccount(account: CurrencyAccount): Try[Either[TransferServiceErrors, Unit]] =
          Success(Right(accounts.update(account.accountNumber.value, account)))

        override def postAccount(account: CurrencyAccount): Try[Either[TransferServiceErrors, Unit]] =
          Success(Right(accounts.update(account.accountNumber.value, account)))

        override def modifyAccountsAtomically(
          fromAccountNumber: AccountNumber,
          toAccountNumber: AccountNumber
        )(
          update: (
            CurrencyAccount,
            CurrencyAccount
          ) => Either[TransferServiceErrors, (CurrencyAccount, CurrencyAccount)]
        ): Try[Either[TransferServiceErrors, domain.model.AtomicTransferResult]] =
          Success {
            for {
              fromAccount <- accounts.get(fromAccountNumber.value).toRight(AccountDoesNotExist)
              toAccount   <- accounts.get(toAccountNumber.value).toRight(AccountDoesNotExist)
              updated     <- update(fromAccount, toAccount)
            } yield {
              val (updatedFrom, updatedTo) = updated
              accounts.update(fromAccountNumber.value, updatedFrom)
              accounts.update(toAccountNumber.value, updatedTo)
              domain.model.AtomicTransferResult(fromAccount, toAccount, updatedFrom, updatedTo)
            }
          }
      }
  }

  // Eval wrappers to allow reuse of the Eval-based datastore in a concurrent Future test
  private final class EvalWrapper[A](val value: A)
  private object EvalWrapper {
    given cats.Monad[EvalWrapper] with
      override def pure[A](x: A): EvalWrapper[A]                                             = new EvalWrapper(x)
      override def flatMap[A, B](fa: EvalWrapper[A])(f: A => EvalWrapper[B]): EvalWrapper[B] = f(fa.value)
      override def tailRecM[A, B](a: A)(f: A => EvalWrapper[Either[A, B]]): EvalWrapper[B] =
        f(a).value match {
          case Left(next) => tailRecM(next)(f)
          case Right(b)   => EvalWrapper(b)
        }
  }

  private final class EvalWrapperGateway(delegate: infrastructure.dataStores.InMemoryEvalDataStore)
      extends AccountGatewayAlg[EvalWrapper] {
    override def getAllAccounts: EvalWrapper[Seq[CurrencyAccount]] = EvalWrapper(delegate.getAllAccounts.value)
    override def getAccount(accountNumber: AccountNumber): EvalWrapper[Either[TransferServiceErrors, CurrencyAccount]] =
      EvalWrapper(delegate.getAccount(accountNumber).value)
    override def updateAccount(account: CurrencyAccount): EvalWrapper[Either[TransferServiceErrors, Unit]] =
      EvalWrapper(delegate.updateAccount(account).value)
    override def postAccount(account: CurrencyAccount): EvalWrapper[Either[TransferServiceErrors, Unit]] =
      EvalWrapper(delegate.postAccount(account).value)
    override def modifyAccountsAtomically(
      fromAccountNumber: AccountNumber,
      toAccountNumber: AccountNumber
    )(
      update: (CurrencyAccount, CurrencyAccount) => Either[TransferServiceErrors, (CurrencyAccount, CurrencyAccount)]
    ): EvalWrapper[Either[TransferServiceErrors, domain.model.AtomicTransferResult]] =
      EvalWrapper(delegate.modifyAccountsAtomically(fromAccountNumber, toAccountNumber)(update).value)
  }

  private final class EvalWrapperLogger(delegate: LoggingAlg[Try]) extends LoggingAlg[EvalWrapper] {
    override def info(msg: String): EvalWrapper[Unit]                 = EvalWrapper(delegate.info(msg).get)
    override def warn(msg: String): EvalWrapper[Unit]                 = EvalWrapper(delegate.warn(msg).get)
    override def error(msg: String, ex: Throwable): EvalWrapper[Unit] = EvalWrapper(delegate.error(msg, ex).get)
  }
}
