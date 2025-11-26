package infrastructure

import java.util.Currency

import org.apache.pekko.actor.ActorRef
import org.apache.pekko.http.scaladsl.marshalling.Marshal
import org.apache.pekko.http.scaladsl.model._
import org.apache.pekko.http.scaladsl.server.Route
import org.apache.pekko.http.scaladsl.testkit.ScalatestRouteTest
import cats.Eval
import domain.model.{AccountNumber, CurrencyAccount, CurrencyAmount}
import infrastructure.actors.AccountActor.{PostNewAccount, TransferBetweenAccounts}
import infrastructure.actors.{AccountActor, AccountRoutes}
import infrastructure.dataStores.InMemoryEvalDataStore
import infrastructure.loggers.EvalLogger
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers
import service.AccountTransferService
import scala.concurrent.duration._

class CurrencyAccountRoutesUnitTest
    extends AnyFreeSpec
    with Matchers
    with ScalaFutures
    with ScalatestRouteTest
    with AccountRoutes {

  private val gbp: Currency = Currency.getInstance("GBP")

  val accounts: Seq[CurrencyAccount] = Vector(
    CurrencyAccount(AccountNumber("Account1"), CurrencyAmount(100), gbp),
    CurrencyAccount(AccountNumber("Account2"), CurrencyAmount(250), gbp),
    CurrencyAccount(AccountNumber("Account3"), CurrencyAmount(5637), gbp),
    CurrencyAccount(AccountNumber("Account4"), CurrencyAmount(573.53), gbp),
    CurrencyAccount(AccountNumber("Account5"), CurrencyAmount(250), gbp)
  )

  val dataStore: InMemoryEvalDataStore =
    new InMemoryEvalDataStore

  accounts.foreach(account => dataStore.postAccount(account).value)

  val logger: EvalLogger = new EvalLogger

  val transferService: AccountTransferService[Eval] =
    new AccountTransferService[Eval](dataStore, logger)

  override val currencyAccountActor: ActorRef =
    system.actorOf(AccountActor.props(transferService), "currencyAccounts")

  lazy val routes: Route = accountRoutes
  private val sealedRoutes: Route = Route.seal(routes)

  "CurrencyAccountRoutes" - {
    "(GET :/api/accounts) should" - {
      "return all accounts" in {
        val request = HttpRequest(uri = "/api/accounts")

        request ~> routes ~> check {
          status shouldBe StatusCodes.OK
          contentType shouldBe ContentTypes.`application/json`

          responseAs[Seq[CurrencyAccount]] should contain(
            CurrencyAccount(AccountNumber("Account1"), CurrencyAmount(100.0), gbp)
          )
          responseAs[Seq[CurrencyAccount]] should contain(
            CurrencyAccount(AccountNumber("Account5"), CurrencyAmount(250.0), gbp)
          )
        }
      }
    }

    "(POST :/api/accounts) should" - {
      "post a new account" in {
        val newAccountPost = PostNewAccount(
          "SOME_ACCOUNT_NUMBER",
          BigDecimal(50.0),
          gbp
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
          responseAs[String] shouldBe "40 has been transferred from Account1 to Account2"
        }

      }
      "return an error when attempting to transfer from a non-existing account" in {
        val transferRequest = TransferBetweenAccounts("NON_EXISTING", "Account2", 40)

        val eventualEntity = Marshal(transferRequest).to[MessageEntity]

        val transferEntity = eventualEntity.futureValue

        val request = Put("/api/accounts/transfer").withEntity(transferEntity)

        request ~> routes ~> check {
          status shouldBe StatusCodes.BadRequest
          contentType shouldBe ContentTypes.`application/json`
        }

      }
    }

    "Swagger docs" - {
      "expose the customised server URL in the OpenAPI YAML" in {
        val request = Get("/docs/docs.yaml")

        request ~> sealedRoutes ~> check {
          status shouldBe StatusCodes.OK
          contentType.mediaType.value shouldBe "application/yaml"
          val yaml = responseEntity.toStrict(1.second).futureValue.data.utf8String
          yaml should include("http://localhost:8081")
        }
      }
    }
  }

}
