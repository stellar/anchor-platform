package org.stellar.reference.di

import io.ktor.client.*
import io.ktor.client.plugins.*
import org.jetbrains.exposed.sql.Database
import org.stellar.reference.callbacks.customer.CustomerService
import org.stellar.reference.callbacks.rate.RateService
import org.stellar.reference.client.PaymentClient
import org.stellar.reference.client.PlatformClient
import org.stellar.reference.dao.JdbcCustomerRepository
import org.stellar.reference.dao.JdbcQuoteRepository
import org.stellar.reference.dao.JdbcTransactionKYCRepository
import org.stellar.reference.event.EventService
import org.stellar.reference.sep24.DepositService
import org.stellar.reference.sep24.WithdrawalService
import org.stellar.reference.service.SepHelper
import org.stellar.reference.service.sep31.ReceiveService
import org.stellar.sdk.KeyPair
import org.stellar.sdk.Server
import org.stellar.sdk.SorobanServer

object ServiceContainer {
  private val config = ConfigContainer.getInstance().config
  val eventService = EventService()
  val horizon = Server(config.appSettings.horizonEndpoint)
  val rpc = SorobanServer(config.appSettings.rpcEndpoint)
  val sep24PaymentClient = PaymentClient(horizon, rpc, KeyPair.fromSecretSeed(config.sep24.secret))
  val sepHelper = SepHelper(config)
  val depositService = DepositService(config, sep24PaymentClient)
  val withdrawalService = WithdrawalService(config)
  val receiveService = ReceiveService(config)

  private val database =
    Database.connect(
      "jdbc:postgresql://${config.dataSettings.url}/${config.dataSettings.database}",
      driver = "org.postgresql.Driver",
      user = config.dataSettings.user,
      password = config.dataSettings.password,
    )
  private val customerRepo = JdbcCustomerRepository(database)
  private val transactionKYCRepo = JdbcTransactionKYCRepository(database)
  private val quotesRepo = JdbcQuoteRepository(database)
  val customerService = CustomerService(customerRepo, transactionKYCRepo, sepHelper)
  val rateService = RateService(quotesRepo)

  val platform =
    PlatformClient(
      HttpClient {
        install(HttpTimeout) {
          requestTimeoutMillis = 5000
          connectTimeoutMillis = 5000
          socketTimeoutMillis = 5000
        }
      },
      config.appSettings.platformApiEndpoint,
    )
}
