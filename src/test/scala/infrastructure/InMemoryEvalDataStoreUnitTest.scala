package infrastructure

import infrastructure.dataStores.InMemoryEvalDataStore
import domain.model.{Currency, CurrencyAccount}
import org.scalatest.{FreeSpec, Matchers}
import service.TransferServiceErrors

class InMemoryEvalDataStoreUnitTest extends FreeSpec with Matchers with  TransferServiceErrors{

  "InMemoryAccountDataStore" - {
    "ListAllAccounts should list accounts correctly" in new TestSuite {
      val result: Seq[CurrencyAccount] = testDataStore.getAllAccounts.value

      result.size shouldBe 6
      result should contain(testAccount3)
      result should contain(testAccount2)
    }

    "GetAccount should" - {
      "return the correct account" in new TestSuite {
        testDataStore.getAccount("4444").value shouldBe Right(testAccount4)
      }

      "return AccountDoesNotExist when a non existing account is requested" in new TestSuite {
        testDataStore.getAccount("SOME_DUMMY_ACCOUNT").value.toString shouldBe Left(AccountDoesNotExist).toString
      }
    }

    "UpdateAccount should" - {
      "only update an existing account" in new TestSuite {
        testDataStore.updateAccount(nonExistingAccount).value == Left(FailedToUpdateAccount)
      }
      "update the correct account" in new TestSuite {
        val expectedNumber = 4827.32
        testDataStore.updateAccount(testAccount2.copy(balance = expectedNumber))
        testDataStore.getAccount("2222").value shouldBe Right(
          CurrencyAccount("2222", expectedNumber, Currency.GBP)
        )
      }
    }

    "PostAccount should" - {
      "post a new account into the Datastore" in new TestSuite {
        testDataStore.postAccount(someAccount) === Right(Unit)
      }
      "not post an account that already exists" in new TestSuite {
        testDataStore.postAccount(someAccount) //Account is posted into Datastore
        testDataStore.postAccount(someAccount).value == Left(AccountAlreadyExists)
      }
    }

  }

  // Test that it returns failure

  private class TestSuite {

    val testDataStore: InMemoryEvalDataStore =
      new InMemoryEvalDataStore

    val testAccount1 = CurrencyAccount("1111", 10.1, Currency.GBP)
    val testAccount2 = CurrencyAccount("2222", 22.1, Currency.GBP)
    val testAccount3 = CurrencyAccount("3333", 33.1, Currency.GBP)
    val testAccount4 = CurrencyAccount("4444", 44.1, Currency.USD)
    val testAccount5 = CurrencyAccount("5555", 55.1, Currency.EUR)
    val testAccount6 = CurrencyAccount("6666", 66.1, Currency.USD)


    val someAccount = CurrencyAccount("SOME_IBAN", 192, Currency.EUR)

    val nonExistingAccount = CurrencyAccount("DUMMY_ACCOUNT", 1.1, Currency.EUR)

    val accounts = Vector(
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
