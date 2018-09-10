package infrastructure.loggers

import cats.Eval
import com.typesafe.scalalogging.LazyLogging
import service.LoggingAlg

class EvalLogger extends LoggingAlg[Eval] with LazyLogging {

  override def info(msg: String): Eval[Unit] = Eval.now { logger.info(msg) }

  override def warn(msg: String): Eval[Unit] = Eval.now { logger.warn(msg) }

  override def error(msg: String, ex: Throwable): Eval[Unit] = Eval.now { logger.error(msg, ex) }
}
