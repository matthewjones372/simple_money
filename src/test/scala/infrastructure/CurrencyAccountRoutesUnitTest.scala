package infrastructure

import akka.actor.ActorRef
import akka.http.scaladsl.marshalling.Marshal
import akka.http.scaladsl.model.{ContentTypes, HttpRequest, MessageEntity, StatusCodes}
import akka.http.scaladsl.server.Route
import akka.http.scaladsl.testkit.ScalatestRouteTest
import cats.Eval
import de.heikoseeberger.akkahttpcirce.FailFastCirceSupport
import infrastructure.actors.AccountActor.{HttpResponse, PostNewAccount, TransferBetweenAccounts}
import infrastructure.actors.{AccountActor, AccountRoutes}
import infrastructure.dataStores.InMemoryEvalDataStore
import infrastructure.loggers.EvalLogger
import domain.model.{AccountNumber, Currency, CurrencyAccount, CurrencyAmount}
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.{FreeSpec, Matchers}
import service.AccountTransferService

class CurrencyAccountRoutesUnitTest
    extends FreeSpec
    with Matchers
    with ScalaFutures
    with ScalatestRouteTest
    with AccountRoutes
    with FailFastCirceSupport {

  val accounts: Seq[CurrencyAccount] = Vector(
    CurrencyAccount(AccountNumber("Account1"), CurrencyAmount(100), Currency.GBP),
    CurrencyAccount(AccountNumber("Account2"), CurrencyAmount(250), Currency.GBP),
    CurrencyAccount(AccountNumber("Account3"), CurrencyAmount(5637), Currency.GBP),
    CurrencyAccount(AccountNumber("Account4"), CurrencyAmount(573.53), Currency.GBP),
    CurrencyAccount(AccountNumber("Account5"), CurrencyAmount(250), Currency.GBP),
  )

  val dataStore: InMemoryEvalDataStore =
    new InMemoryEvalDataStore

  accounts.foreach(dataStore.postAccount)

  val logger: EvalLogger = new EvalLogger

  val transferService: AccountTransferService[Eval] =
    new AccountTransferService[Eval](dataStore, logger)

  override val currencyAccountActor: ActorRef =
    system.actorOf(AccountActor.props(transferService), "currencyAccounts")

  lazy val routes: Route = accountRoutes

  "CurrencyAccountRoutes" - {
    "(GET :/api/accounts) should" - {
      "return all accounts" in {
        val request = HttpRequest(uri = "/api/accounts")

        request ~> routes ~> check {
          status shouldBe StatusCodes.OK
          contentType shouldBe ContentTypes.`application/json`

          responseAs[Seq[CurrencyAccount]] should contain(
            CurrencyAccount(AccountNumber("Account1"), CurrencyAmount(100.0), Currency.GBP)
          )
          responseAs[Seq[CurrencyAccount]] should contain(
            CurrencyAccount(AccountNumber("Account5"), CurrencyAmount(250.0), Currency.GBP)
          )
        }
      }
    }

    "(POST :/api/accounts) should" - {
      "post a new account" in {
        val newAccountPost = PostNewAccount(
          "SOME_ACCOUNT_NUMBER",
          BigDecimal(50.0),
          Currency.GBP
        )

        val eventualEntity = Marshal(newAccountPost).to[MessageEntity]

        val newAccountEntity = eventualEntity.futureValue

        val request = Post("/api/accounts/").withEntity(newAccountEntity)

        request ~> routes ~> check {
          status shouldBe StatusCodes.OK
        }
      }
    }

    "(PUT :/accounts/transfer) should" - {
      "transfer between two accounts" in {
        val transferRequest =
          TransferBetweenAccounts("Account1", "Account2", BigDecimal(40))

        val eventualEntity = Marshal(transferRequest).to[MessageEntity]

        val transferEntity = eventualEntity.futureValue

        val request = Put("/api/accounts/transfer").withEntity(transferEntity)


        request ~> routes ~> check {
          status shouldBe StatusCodes.OK
          contentType shouldBe ContentTypes.`application/json`
          responseAs[HttpResponse] shouldBe HttpResponse(
            "40 has been transferred from Account1 to Account2"
          )
        }

      }
      "return an error when attempting to transfer from a non-existing account" in {
        val
        transferRequest= TransferBetweenAccounts("NON_EXISTING",
          "Account2", 40
        )

        val eventualEntity = Marshal(transferRequest).to[MessageEntity]

        val transferEntity = eventualEntity.futureValue

        val request = Put("/api/accounts/transfer").withEntity(transferEntity)

        request ~> routes ~> check {
          status shouldBe StatusCodes.OK
          contentType shouldBe ContentTypes.`application/json`
          responseAs[HttpResponse].response.split(" ") should contain("AccountDoesNotExist")
        }

      }
    }
  }

}
