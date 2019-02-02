package infrastructure

import infrastructure.dataStores.InMemoryEvalDataStore
import domain.model.{CurrencyAmount, AccountNumber, Currency, CurrencyAccount}
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
        testDataStore.getAccount(AccountNumber("4444")).value shouldBe Right(testAccount4)
      }

      "return AccountDoesNotExist when a non existing account is requested" in new TestSuite {
        testDataStore.getAccount(AccountNumber("SOME_DUMMY_ACCOUNT")).value.toString shouldBe Left(AccountDoesNotExist).toString
      }
    }

    "UpdateAccount should" - {
      "only update an existing account" in new TestSuite {
        testDataStore.updateAccount(nonExistingAccount).value == Left(FailedToUpdateAccount)
      }
      "update the correct account" in new TestSuite {
        val expectedNumber = CurrencyAmount(4827.32)
        testDataStore.updateAccount(testAccount2.copy(balance = expectedNumber))
        testDataStore.getAccount(AccountNumber("2222")).value shouldBe Right(
          CurrencyAccount(AccountNumber("2222"), expectedNumber, Currency.GBP)
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

    val testAccount1 = CurrencyAccount(AccountNumber("1111"), CurrencyAmount(10.1), Currency.GBP)
    val testAccount2 = CurrencyAccount(AccountNumber("2222"), CurrencyAmount(22.1), Currency.GBP)
    val testAccount3 = CurrencyAccount(AccountNumber("3333"), CurrencyAmount(33.1), Currency.GBP)
    val testAccount4 = CurrencyAccount(AccountNumber("4444"), CurrencyAmount(44.1), Currency.USD)
    val testAccount5 = CurrencyAccount(AccountNumber("5555"), CurrencyAmount(55.1), Currency.EUR)
    val testAccount6 = CurrencyAccount(AccountNumber("6666"), CurrencyAmount(66.1), Currency.USD)


    val someAccount =
      CurrencyAccount(AccountNumber("SOME_ACCOUNT_NUMBER"), CurrencyAmount(192), Currency.EUR)

    val nonExistingAccount = CurrencyAccount(AccountNumber("DUMMY_ACCOUNT"), CurrencyAmount(1.1), Currency.EUR)

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
