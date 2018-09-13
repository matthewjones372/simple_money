package domain.algebra

import scala.language.higherKinds

trait LoggingAlg[F[_]] {

  def info(msg: String): F[Unit]

  def warn(msg: String): F[Unit]

  def error(msg: String, ex: Throwable): F[Unit]
}
