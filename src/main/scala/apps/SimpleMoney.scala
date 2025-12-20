package apps

import http.AccountRoutes
import service.AccountTransferService
import service.AccountService
import zio.*
import zio.http.*
import zio.http.endpoint.openapi.SwaggerUI
import zio.logging.backend.SLF4J

object SimpleMoney extends ZIOAppDefault:

  private val port: Int = 8081

  private val appLayer: ULayer[AccountService & AccountTransferService] =
    AccountService.layer ++
      AccountTransferService.layer

  private val swaggerRoutes: Routes[Any, Response] =
    SwaggerUI.routes("docs", AccountRoutes.openAPISpec)

  private val allRoutes = AccountRoutes.routes ++ swaggerRoutes

  override val bootstrap: ZLayer[Any, Any, Unit] =
    Runtime.removeDefaultLoggers >>> SLF4J.slf4j

  override def run: ZIO[Any, Any, Any] =
    (for
      _ <- ZIO.logInfo(s"Server online at http://localhost:$port/")
      _ <- ZIO.logInfo(s"Swagger UI available at http://localhost:$port/docs")
      _ <- ZIO.logInfo(s"API available at http://localhost:$port/api/accounts")
      _ <- Server.serve(allRoutes)
    yield ())
      .provide(
        appLayer,
        Server.defaultWithPort(port)
      )
