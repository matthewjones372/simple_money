package infrastructure.actors

import java.text.DecimalFormat

import akka.actor.{ Actor, ActorLogging, Props }
import akka.util.Timeout
import cats.Eval
import infrastructure.actors.AccountActor.{ ActionPerformed, transferBetweenAccounts }
import service.AccountTransferService

import scala.concurrent.duration._

class AccountActor(transferService: AccountTransferService[Eval]) extends Actor with ActorLogging {

  implicit lazy val timeout: Timeout = Timeout(5.seconds)

  lazy val formatter = new DecimalFormat("#.##")

  override def receive: PartialFunction[Any, Unit] = {

    case transferBetweenAccounts(fromIban, toIban, amount) =>
      val response: Eval[String] = transferService.accountTransfer(fromIban, toIban, amount) map {
        case Right(_) =>
          s"${formatter.format(amount)} has been transferred from $fromIban to $toIban"
        case Left(err) =>
          s"Transfer unsuccessful from account $fromIban to $toIban error: ${err.toString}"
      }
      sender() ! ActionPerformed(response.value)
    case getAccounts =>
      sender() ! transferService.listAllAccounts.value
  }
}

object AccountActor {

  final case object getAccounts

  final case class ActionPerformed(response: String)

  final case class getCurrencyAccount(iban: String)

  final case class transferBetweenAccounts(
      fromIban: String,
      toIban: String,
      amount: Double
  )

  def props(transferService: AccountTransferService[Eval]): Props =
    Props(new AccountActor(transferService))

}
