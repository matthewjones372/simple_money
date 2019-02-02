package infrastructure.actors

import java.text.DecimalFormat

import akka.actor.{Actor, ActorLogging, Props}
import akka.util.Timeout
import cats.Eval
import domain.model.{AccountNumber, Currency, CurrencyAccount, CurrencyAmount}
import infrastructure.actors.AccountActor._
import service.AccountTransferService

import scala.concurrent.duration._

class AccountActor(transferService: AccountTransferService[Eval]) extends Actor with ActorLogging {

  implicit lazy val timeout: Timeout = Timeout(5.seconds) // TODO: Load in from config file

  lazy val formatter = new DecimalFormat("#.##")

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
          s"Successfully added $accountNumber into the Datastore"
        case Left(err) =>
          s"Could not add $accountNumber into the data store reason: $err"
      }
      sender() ! HttpResponse(response.value)

    case TransferBetweenAccounts(fromAccountNumber, toAccountNumber, amount) =>
      val response: Eval[String] = transferService.accountTransfer(
        AccountNumber.fromString(fromAccountNumber),
        AccountNumber.fromString(toAccountNumber),
        CurrencyAmount.fromBigDecimal(amount)) map {
        case Right(_) =>
          s"${formatter.format(amount)} has been transferred from $fromAccountNumber to $toAccountNumber"
        case Left(err) =>
          s"Transfer unsuccessful from account $fromAccountNumber to $toAccountNumber error: ${err.toString}"
      }
      sender() ! HttpResponse(response.value)

  }
}

object AccountActor {

  final case object GetAccounts

  final case class PostNewAccount(accountNumber: String, balance: BigDecimal, currency: Currency)

  final case class HttpResponse(response: String)


  final case class GetCurrencyAccount(accountNumber: String)

  final case class TransferBetweenAccounts(fromAccountNumber: String,
                                           toAccountNumber: String,
                                           amount: BigDecimal)

  def props(transferService: AccountTransferService[Eval]): Props =
    Props(new AccountActor(transferService))

}
