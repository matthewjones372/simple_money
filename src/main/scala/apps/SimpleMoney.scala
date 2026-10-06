package apps

import http.AccountRoutes
import service.AccountTransferService
import service.AccountService
import zio.*
import zio.http.*
import zio.http.endpoint.openapi.SwaggerUI
import zio.logging.backend.SLF4J

object SimpleMoney extends ZIOAppDefault:

  // Read from the PORT environment variable or the port system property
  val portConfig: Config[Int] = Config.int("port").withDefault(8081)

  private val appLayer: ULayer[AccountTransferService] =
    AccountService.layer >>> AccountTransferService.layer

  private val swaggerRoutes: Routes[Any, Response] =
    SwaggerUI.routes("docs", AccountRoutes.openAPISpec)

  private val allRoutes = AccountRoutes.routes ++ swaggerRoutes

  override val bootstrap: ZLayer[Any, Any, Unit] =
    Runtime.removeDefaultLoggers >>> SLF4J.slf4j

  override def run: ZIO[Any, Any, Any] =
    (for
      port <- Server.install(allRoutes)
      _    <- ZIO.logInfo(s"Listening on port $port, with Swagger UI at /docs and the API at /api/accounts")
      _    <- ZIO.never
    yield ())
      .provide(
        appLayer,
        ZLayer.fromZIO(ZIO.config(portConfig)).flatMap(port => Server.defaultWithPort(port.get))
      )
