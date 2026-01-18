package org.stellar.reference.di

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.cors.routing.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.stellar.reference.callbacks.customer.customer
import org.stellar.reference.callbacks.interactive.sep24Interactive
import org.stellar.reference.callbacks.rate.rate
import org.stellar.reference.callbacks.test.testCustomer
import org.stellar.reference.data.AuthSettings
import org.stellar.reference.event.event
import org.stellar.reference.plugins.RequestExceptionHandlerPlugin
import org.stellar.reference.plugins.RequestLoggerPlugin
import org.stellar.reference.plugins.testSep31
import org.stellar.reference.sep24.sep24
import org.stellar.reference.sep24.testSep24

const val AUTH_CONFIG_ENDPOINT = "endpoint-auth"

object ReferenceServerContainer {
  lateinit var server: NettyApplicationEngine

  fun startServer(wait: Boolean): NettyApplicationEngine {
    server =
      embeddedServer(Netty, port = config.appSettings.port) {
        install(ContentNegotiation) { json() }
        configureAuth()
        configureRouting()
        install(CORS) {
          anyHost()
          allowHeader(HttpHeaders.Authorization)
          allowHeader(HttpHeaders.ContentType)
        }
        install(RequestLoggerPlugin)
        install(RequestExceptionHandlerPlugin)
      }

    return server.start(wait = wait)
  }

  private val config = ConfigContainer.getInstance().config

  private fun Application.configureRouting() = routing {
    sep24(
      ServiceContainer.sepHelper,
      ServiceContainer.depositService,
      ServiceContainer.withdrawalService,
      config.sep24.interactiveJwtKey
    )
    event(ServiceContainer.eventService, config.appSettings.isTest)
    customer(ServiceContainer.customerService)
    rate(ServiceContainer.rateService)
    sep24Interactive()

    if (config.appSettings.enableTest) {
      testSep24(
        ServiceContainer.sepHelper,
        ServiceContainer.depositService,
        ServiceContainer.withdrawalService,
        config.sep24.interactiveJwtKey
      )
      testSep31(ServiceContainer.receiveService)
    }
    if (config.appSettings.isTest) {
      testCustomer(ServiceContainer.customerService)
    }
  }

  private fun Application.configureAuth() {
    when (config.authSettings.type) {
      AuthSettings.Type.JWT ->
        authentication {
          jwt(AUTH_CONFIG_ENDPOINT) {
            verifier(
              JWT.require(Algorithm.HMAC256(config.authSettings.platformToAnchorSecret)).build()
            )
            validate { credential ->
              val principal = JWTPrincipal(credential.payload)
              if (principal.payload.expiresAt.time < System.currentTimeMillis()) {
                null
              } else {
                principal
              }
            }
            challenge { _, _ ->
              call.respond(HttpStatusCode.Unauthorized, "Token is invalid or expired")
            }
          }
        }
      AuthSettings.Type.API_KEY -> {
        TODO("API key auth not implemented yet")
      }
      AuthSettings.Type.NONE -> {
        log.warn("Authentication is disabled. Endpoints are not secured.")
        authentication { basic(AUTH_CONFIG_ENDPOINT) { skipWhen { true } } }
      }
    }
  }
}
