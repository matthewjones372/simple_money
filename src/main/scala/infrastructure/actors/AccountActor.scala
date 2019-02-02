package infrastructure.actors

import akka.actor.{Actor, ActorLogging, Props}
import akka.http.scaladsl.model.StatusCodes._
import akka.http.scaladsl.model.{ContentTypes, HttpEntity, HttpResponse}
import akka.util.Timeout
import cats.Eval
import domain.model.{AccountNumber, Currency, CurrencyAccount, CurrencyAmount}
import infrastructure.actors.AccountActor._
import service.AccountTransferService

import scala.concurrent.duration._

class AccountActor(transferService: AccountTransferService[Eval]) extends Actor with ActorLogging {

  implicit lazy val timeout: Timeout = Timeout(5.seconds) // TODO: Load in from config file


  override def receive: PartialFunction[Any, Unit] = {

    case GetAccounts =>
      sender() ! transferService.listAllAccounts.value

    case PostNewAccount(accountNumber, balance, currency) =>
      val response = transferService.addNewAccount(
        CurrencyAccount(AccountNumber.fromString(accountNumber),
          CurrencyAmount.fromBigDecimal(balance),
          currency)
      ) map {
        case Right(_) =>
          HttpResponse(entity = HttpEntity(ContentTypes.`application/json`, s"Successfully added $accountNumber into the datastore"))
        case Left(err) =>
          HttpResponse(BadRequest, entity=HttpEntity(ContentTypes.`application/json`, s"Could not add $accountNumber into the data store reason: $err"))
      }
      sender() ! response.value

    case TransferBetweenAccounts(fromAccountNumber, toAccountNumber, amount) =>
      val response: Eval[HttpResponse] = transferService.accountTransfer(
        AccountNumber.fromString(fromAccountNumber),
        AccountNumber.fromString(toAccountNumber),
        CurrencyAmount.fromBigDecimal(amount)).map{
        case Right(_) =>
          HttpResponse(entity = HttpEntity(ContentTypes.`application/json`, s"$amount has been transferred from $fromAccountNumber to $toAccountNumber"))
        case Left(err) =>
          HttpResponse(BadRequest, entity = HttpEntity(ContentTypes.`application/json`,
            s"Transfer unsuccessful from account $fromAccountNumber to $toAccountNumber error: ${err.toString}"))
      }

      sender() ! response.value
  }
}

object AccountActor {

  final case object GetAccounts

  final case class PostNewAccount(accountNumber: String, balance: BigDecimal, currency: Currency)

  final case class GetCurrencyAccount(accountNumber: String)

  final case class TransferBetweenAccounts(fromAccountNumber: String,
                                           toAccountNumber: String,
                                           amount: BigDecimal)

  def props(transferService: AccountTransferService[Eval]): Props =
    Props(new AccountActor(transferService))
}
