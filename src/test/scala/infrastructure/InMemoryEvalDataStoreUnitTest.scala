package infrastructure

import java.util.Currency

import domain.model.{AccountNumber, AtomicTransferResult, CurrencyAccount, CurrencyAmount}
import infrastructure.dataStores.InMemoryEvalDataStore
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers
import service.TransferServiceErrors
import service.TransferServiceErrors._

class InMemoryEvalDataStoreUnitTest extends AnyFreeSpec with Matchers {

  "InMemoryAccountDataStore" - {
    "ListAllAccounts should list accounts correctly" in new TestSuite {
      val result: Seq[CurrencyAccount] = testDataStore.getAllAccounts.value

      result.size shouldBe 6
      result should contain(testAccount3)
      result should contain(testAccount2)
    }

    "GetAccount should" - {
      "return the correct account" in new TestSuite {
        testDataStore.getAccount(AccountNumber("4444")).value shouldBe Right(testAccount4)
      }

      "return AccountDoesNotExist when a non existing account is requested" in new TestSuite {
        testDataStore
          .getAccount(AccountNumber("SOME_DUMMY_ACCOUNT"))
          .value
          .toString shouldBe Left(AccountDoesNotExist).toString
      }
    }

    "UpdateAccount should" - {
      "only update an existing account" in new TestSuite {
        testDataStore.updateAccount(nonExistingAccount).value shouldBe Left(FailedToUpdateAccount)
      }
      "update the correct account" in new TestSuite {
        val expectedNumber = CurrencyAmount(4827.32)
        testDataStore.updateAccount(testAccount2.copy(balance = expectedNumber)).value
        testDataStore
          .getAccount(AccountNumber("2222"))
          .value shouldBe Right(
          CurrencyAccount(AccountNumber("2222"), expectedNumber, testAccount2.currency)
        )
      }
    }

    "PostAccount should" - {
      "post a new account into the Datastore" in new TestSuite {
        testDataStore.postAccount(someAccount).value shouldBe Right(())
      }
      "not post an account that already exists" in new TestSuite {
        testDataStore.postAccount(someAccount).value //Account is posted into Datastore
        testDataStore.postAccount(someAccount).value shouldBe Left(AccountAlreadyExists)
      }
    }

    "Atomic transfers should" - {
      "block concurrent modifications to the same accounts" in new TestSuite {
        import scala.concurrent.ExecutionContext.Implicits.global
        import scala.concurrent.{Await, Future}
        import scala.concurrent.duration.DurationInt
        import java.util.concurrent.CountDownLatch

        val startLatch   = new CountDownLatch(1)
        val releaseLatch = new CountDownLatch(1)
        val delayMillis  = 200L

        val transferFuture = Future {
          testDataStore
            .modifyAccountsAtomically(testAccount1.accountNumber, testAccount2.accountNumber) { (from, to) =>
              startLatch.countDown()
              releaseLatch.await()
              Right((from, to))
            }
            .value
        }

        startLatch.await() // ensure the transfer has acquired both locks

        val updateFuture = Future {
          val start     = System.nanoTime()
          val result    = testDataStore.updateAccount(testAccount1).value
          val elapsedMs = (System.nanoTime() - start) / 1000000
          (result, elapsedMs)
        }

        Thread.sleep(delayMillis.toLong)
        releaseLatch.countDown()

        val (updateResult, elapsedMs) = Await.result(updateFuture, 2.seconds)
        Await.result(transferFuture, 2.seconds) shouldBe Right(
          AtomicTransferResult(
            testAccount1,
            testAccount2,
            testAccount1,
            testAccount2
          )
        )

        updateResult shouldBe Right(())
        // Allow a small tolerance for scheduling jitter while still ensuring the atomic lock blocks
        // concurrent updates for approximately the configured delay.
        elapsedMs should be >= delayMillis - 10
      }
    }

  }

  // Test that it returns failure

  private class TestSuite {

    val testDataStore: InMemoryEvalDataStore =
      new InMemoryEvalDataStore

    private val gbp = Currency.getInstance("GBP")
    private val usd = Currency.getInstance("USD")
    private val eur = Currency.getInstance("EUR")

    val testAccount1 = CurrencyAccount(AccountNumber("1111"), CurrencyAmount(10.1), gbp)
    val testAccount2 = CurrencyAccount(AccountNumber("2222"), CurrencyAmount(22.1), gbp)
    val testAccount3 = CurrencyAccount(AccountNumber("3333"), CurrencyAmount(33.1), gbp)
    val testAccount4 = CurrencyAccount(AccountNumber("4444"), CurrencyAmount(44.1), usd)
    val testAccount5 = CurrencyAccount(AccountNumber("5555"), CurrencyAmount(55.1), eur)
    val testAccount6 = CurrencyAccount(AccountNumber("6666"), CurrencyAmount(66.1), usd)

    val someAccount =
      CurrencyAccount(AccountNumber("SOME_ACCOUNT_NUMBER"), CurrencyAmount(192), eur)

    val nonExistingAccount = CurrencyAccount(AccountNumber("DUMMY_ACCOUNT"), CurrencyAmount(1.1), eur)

    val accounts: Vector[CurrencyAccount] = Vector(
      testAccount1,
      testAccount2,
      testAccount3,
      testAccount4,
      testAccount5,
      testAccount6
    )

    // Place test accounts into datastore
    accounts.foreach(testDataStore.postAccount)
  }

}
