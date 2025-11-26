package domain.algebra

trait LoggingAlg[F[_]] {

  def info(msg: String): F[Unit]

  def warn(msg: String): F[Unit]

  def error(msg: String, ex: Throwable): F[Unit]
}
