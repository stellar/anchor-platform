package org.stellar.anchor.platform.rpc

import io.mockk.every
import io.mockk.mockk
import jakarta.persistence.EntityManagerFactory
import javax.sql.DataSource
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.context.annotation.AnnotationConfigApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.io.DefaultResourceLoader
import org.springframework.data.jpa.repository.config.EnableJpaRepositories
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.orm.jpa.JpaTransactionManager
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean
import org.springframework.orm.jpa.persistenceunit.PersistenceManagedTypes
import org.springframework.orm.jpa.persistenceunit.PersistenceManagedTypesScanner
import org.springframework.orm.jpa.support.OpenEntityManagerInViewInterceptor
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter
import org.springframework.web.context.request.ServletWebRequest
import org.stellar.anchor.api.exception.SepValidationException
import org.stellar.anchor.api.platform.GetTransactionResponse
import org.stellar.anchor.api.rpc.RpcErrorCode
import org.stellar.anchor.api.rpc.RpcRequest
import org.stellar.anchor.api.rpc.RpcResponse
import org.stellar.anchor.asset.DefaultAssetService
import org.stellar.anchor.event.EventService
import org.stellar.anchor.event.EventService.EventQueue.TRANSACTION
import org.stellar.anchor.metrics.MetricsService
import org.stellar.anchor.platform.config.RpcConfig
import org.stellar.anchor.platform.data.JdbcSep24Transaction
import org.stellar.anchor.platform.data.JdbcSep24TransactionRepo
import org.stellar.anchor.platform.data.JdbcSep24TransactionStore
import org.stellar.anchor.platform.data.JdbcSep31Transaction
import org.stellar.anchor.platform.data.JdbcSep31TransactionRepo
import org.stellar.anchor.platform.data.JdbcSep31TransactionStore
import org.stellar.anchor.platform.data.JdbcSep6Transaction
import org.stellar.anchor.platform.data.JdbcSep6TransactionRepo
import org.stellar.anchor.platform.data.JdbcSep6TransactionStore
import org.stellar.anchor.platform.observer.stellar.PaymentObservingAccountsManager
import org.stellar.anchor.platform.service.RpcService
import org.stellar.anchor.platform.service.Sep24DepositInfoNoneGenerator
import org.stellar.anchor.platform.service.Sep31DepositInfoNoneGenerator
import org.stellar.anchor.platform.service.Sep6DepositInfoNoneGenerator
import org.stellar.anchor.platform.service.TransactionService
import org.stellar.anchor.platform.utils.RpcUtil.JSON_RPC_VERSION
import org.stellar.anchor.platform.validator.RequestValidator
import org.stellar.anchor.sep24.Sep24DepositInfoGenerator
import org.stellar.anchor.sep38.Sep38QuoteStore
import org.stellar.anchor.sep6.Sep6DepositInfoGenerator
import org.stellar.anchor.util.GsonUtils
import org.stellar.anchor.util.SepRequestValidator
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer

/**
 * ANCHOR-1587 (HackerOne #4241206). A rejected item of a JSON-RPC batch must leave its transaction
 * as it was, whatever a later item of the same request commits.
 *
 * Runs [RpcService] with the real [RequestOnchainFundsHandler] and the real SEP-6 store against a
 * real PostgreSQL, and reads the rows back over a separate connection. Every scenario runs twice:
 * with an `EntityManager` bound to the thread for the whole request (what open-in-view does, bound
 * by Spring's own [OpenEntityManagerInViewInterceptor]) and without one. Needs Docker.
 *
 * The seed values, the batch and the controls are the ones of the report: two SEP-6 withdrawals in
 * `pending_anchor`, an item for the first that the platform rejects, an item for the second that it
 * accepts.
 */
@Testcontainers
class RpcBatchRejectedItemPostgresTest {

  @Configuration(proxyBeanMethods = false)
  @EnableJpaRepositories(basePackages = ["org.stellar.anchor.platform.data"])
  class JpaConfig {
    @Bean
    fun dataSource(): DataSource =
      DriverManagerDataSource(postgres.jdbcUrl, postgres.username, postgres.password)

    @Bean
    fun entityManagerFactory(dataSource: DataSource): LocalContainerEntityManagerFactoryBean =
      LocalContainerEntityManagerFactoryBean().apply {
        this.dataSource = dataSource
        // Spring Boot's @EntityScan list, minus the converter: it is already registered through
        // the @Convert of JdbcSep38Quote, and Hibernate refuses it twice in a plain context.
        val scanned =
          PersistenceManagedTypesScanner(DefaultResourceLoader())
            .scan("org.stellar.anchor.platform.data")
        setManagedTypes(
          PersistenceManagedTypes.of(
            scanned.managedClassNames.filterNot { it.endsWith("RateFeeConverter") },
            scanned.managedPackages,
          )
        )
        jpaVendorAdapter = HibernateJpaVendorAdapter()
        // What Spring Boot sets: fields without a @Column map to snake_case columns.
        setJpaPropertyMap(
          mapOf(
            "hibernate.physical_naming_strategy" to
              "org.hibernate.boot.model.naming.CamelCaseToUnderscoresNamingStrategy"
          )
        )
      }

    @Bean
    fun transactionManager(entityManagerFactory: EntityManagerFactory): JpaTransactionManager =
      JpaTransactionManager(entityManagerFactory)
  }

  companion object {
    @Container @JvmStatic val postgres = PostgreSQLContainer("postgres:15.2-alpine")

    private lateinit var context: AnnotationConfigApplicationContext

    private const val FIAT_USD = "iso4217:USD"
    private const val STELLAR_USDC =
      "stellar:USDC:GA5ZSEJYB37JRC5AVCIA5MOP4RHTM335X2KGX3IHOJAPP5RE34K4KZVN"
    private const val USDC_CODE = "USDC"
    private const val USDC_ISSUER = "GA5ZSEJYB37JRC5AVCIA5MOP4RHTM335X2KGX3IHOJAPP5RE34K4KZVN"

    private const val WEB_AUTH_ACCOUNT = "GWEBAUTHACCOUNT"
    // The destination the platform refuses for the first item (not the authenticated account).
    private const val MISMATCHED_DESTINATION = "GMISMATCHEDDESTINATION"
    private const val OWN_DESTINATION = "GOWNDESTINATION"
    // The destination whose observing account cannot be stored, after the request was accepted.
    private const val UNSTORABLE_DESTINATION = "GUNSTORABLEDESTINATION"
    private const val BUSY_DESTINATION = "GBUSYDESTINATION"
    private const val BUSY_MEMO = "555555"

    private const val MEMO_IN_USE_MESSAGE =
      "memo_in_use: the requested memo and destination account are already assigned to a pending transaction"

    private val gson = GsonUtils.getInstance()

    @BeforeAll
    @JvmStatic
    fun startContext() {
      // The schema comes from the project's own migrations, so the test reads the real tables.
      Flyway.configure()
        .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
        .locations("classpath:db/migration")
        .load()
        .migrate()
      context = AnnotationConfigApplicationContext(JpaConfig::class.java)
    }

    @AfterAll
    @JvmStatic
    fun closeContext() {
      context.close()
    }
  }

  private lateinit var jdbc: JdbcTemplate
  private lateinit var store6: JdbcSep6TransactionStore
  private lateinit var store24: JdbcSep24TransactionStore
  private lateinit var store31: JdbcSep31TransactionStore
  private lateinit var rpcService: RpcService

  @BeforeEach
  fun setUp() {
    jdbc = JdbcTemplate(context.getBean(DataSource::class.java))
    jdbc.execute("DELETE FROM sep6_transaction")
    jdbc.execute("DELETE FROM sep24_transaction")
    jdbc.execute("DELETE FROM sep31_transaction")
    store6 = JdbcSep6TransactionStore(context.getBean(JdbcSep6TransactionRepo::class.java))
    store24 = JdbcSep24TransactionStore(context.getBean(JdbcSep24TransactionRepo::class.java))
    store31 = JdbcSep31TransactionStore(context.getBean(JdbcSep31TransactionRepo::class.java))

    val eventService: EventService = mockk(relaxed = true)
    every { eventService.createSession(any(), TRANSACTION) } returns mockk(relaxed = true)
    val sepRequestValidator: SepRequestValidator = mockk(relaxed = true)
    every { sepRequestValidator.validateDestinationAccount(any<String>(), any()) } answers
      {
        if (secondArg<String?>() == MISMATCHED_DESTINATION) {
          throw SepValidationException("destination account is not the authenticated account")
        }
      }
    val accountsManager: PaymentObservingAccountsManager = mockk(relaxed = true)
    every { accountsManager.upsertOrThrow(UNSTORABLE_DESTINATION, any()) } throws
      RuntimeException("observing account could not be stored")
    val assetService = DefaultAssetService.fromJsonResource("test_assets.json")
    val handler =
      RequestOnchainFundsHandler(
        store6,
        store24,
        store31,
        mockk<RequestValidator>(relaxed = true),
        assetService,
        mockk<Sep6DepositInfoNoneGenerator>(relaxed = true),
        mockk<Sep24DepositInfoNoneGenerator>(relaxed = true),
        mockk<Sep31DepositInfoNoneGenerator>(relaxed = true),
        accountsManager,
        eventService,
        mockk<MetricsService>(relaxed = true),
        sepRequestValidator,
      )
    val rpcConfig: RpcConfig = mockk(relaxed = true)
    every { rpcConfig.batchSizeLimit } returns 10
    val getTransactionHandler =
      GetTransactionHandler(
        TransactionService(
          store6,
          store24,
          store31,
          mockk<Sep38QuoteStore>(relaxed = true),
          assetService,
          eventService,
          mockk<Sep6DepositInfoGenerator>(relaxed = true),
          mockk<Sep24DepositInfoGenerator>(relaxed = true),
        )
      )
    rpcService = RpcService(listOf(handler, getTransactionHandler), rpcConfig)
  }

  // --- Fixtures ---------------------------------------------------------------------------------

  private fun seedWithdrawal(id: String, amountIn: String, amountOut: String) {
    store6.save(
      JdbcSep6Transaction().also {
        it.id = id
        it.status = "pending_anchor"
        it.kind = "withdrawal"
        it.requestAssetCode = USDC_CODE
        it.requestAssetIssuer = USDC_ISSUER
        it.webAuthAccount = WEB_AUTH_ACCOUNT
        it.amountIn = amountIn
        it.amountOut = amountOut
        it.amountExpected = amountIn
        it.amountFee = "1"
      }
    )
  }

  private fun seedSep24Withdrawal(id: String, amountIn: String, amountOut: String) {
    store24.save(
      JdbcSep24Transaction().also {
        // The SEP-24 store looks rows up by transaction_id and copies it into the key on save.
        it.transactionId = id
        it.status = "pending_anchor"
        it.kind = "withdrawal"
        it.requestAssetCode = USDC_CODE
        it.requestAssetIssuer = USDC_ISSUER
        it.amountIn = amountIn
        it.amountOut = amountOut
        it.amountExpected = amountIn
        it.amountFee = "1"
      }
    )
  }

  private fun seedSep31Receive(id: String, amountIn: String, amountOut: String) {
    store31.save(
      JdbcSep31Transaction().also {
        it.id = id
        it.status = "pending_receiver"
        it.amountIn = amountIn
        it.amountOut = amountOut
        it.amountExpected = amountIn
        it.amountFee = "1"
      }
    )
  }

  /** A transaction that already waits for a payment with [BUSY_MEMO] on [BUSY_DESTINATION]. */
  private fun seedHolderOfBusyMemo() {
    store6.save(
      JdbcSep6Transaction().also {
        it.id = "holder-txn"
        it.status = "pending_user_transfer_start"
        it.kind = "withdrawal"
        it.withdrawAnchorAccount = BUSY_DESTINATION
        it.memo = BUSY_MEMO
        it.memoType = "id"
      }
    )
  }

  private fun requestOnchainFunds(
    rpcId: Int,
    transactionId: String,
    amountIn: String,
    amountOut: String,
    memo: String,
    destination: String,
  ): RpcRequest {
    val fee = (amountIn.toInt() - amountOut.toInt()).toString()
    val params =
      gson.fromJson(
        """
        {"transaction_id":"$transactionId",
         "amount_in":{"amount":"$amountIn","asset":"$STELLAR_USDC"},
         "amount_out":{"amount":"$amountOut","asset":"$FIAT_USD"},
         "fee_details":{"total":"$fee","asset":"$STELLAR_USDC"},
         "memo":"$memo","memo_type":"id","destination_account":"$destination"}
        """,
        Map::class.java,
      )
    return RpcRequest.builder()
      .jsonrpc(JSON_RPC_VERSION)
      .id(rpcId)
      .method("request_onchain_funds")
      .params(params)
      .build()
  }

  /** The report's first item: amounts 500/499, memo 777777, a destination that is refused. */
  private fun rejectedForDestination(rpcId: Int, transactionId: String) =
    requestOnchainFunds(rpcId, transactionId, "500", "499", "777777", MISMATCHED_DESTINATION)

  /** Refused because the memo is already pending on another transaction. */
  private fun rejectedForMemoInUse(rpcId: Int, transactionId: String) =
    requestOnchainFunds(rpcId, transactionId, "500", "499", BUSY_MEMO, BUSY_DESTINATION)

  private fun accepted(rpcId: Int, transactionId: String, memo: String) =
    requestOnchainFunds(rpcId, transactionId, "20", "19", memo, OWN_DESTINATION)

  /** Runs the batch as one HTTP request would: with or without a request-wide entity manager. */
  private fun handleBatch(openInView: Boolean, vararg items: RpcRequest): List<RpcResponse> {
    if (!openInView) return rpcService.handle(items.toList())
    val interceptor =
      OpenEntityManagerInViewInterceptor().apply {
        setEntityManagerFactory(context.getBean(EntityManagerFactory::class.java))
      }
    val request = ServletWebRequest(MockHttpServletRequest())
    interceptor.preHandle(request)
    try {
      return rpcService.handle(items.toList())
    } finally {
      interceptor.afterCompletion(request, null)
    }
  }

  /** The stored row, read over a connection of its own. */
  private fun row(id: String): Map<String, Any?> =
    jdbc.queryForMap(
      """
      SELECT status, amount_in, amount_out, amount_expected, amount_fee, memo, memo_type,
             to_account, withdraw_anchor_account, version
      FROM sep6_transaction WHERE id = ?
      """,
      id,
    )

  private fun untouchedRow(amountIn: String, amountOut: String): Map<String, Any?> =
    mapOf(
      "status" to "pending_anchor",
      "amount_in" to amountIn,
      "amount_out" to amountOut,
      "amount_expected" to amountIn,
      "amount_fee" to "1",
      "memo" to null,
      "memo_type" to null,
      "to_account" to null,
      "withdraw_anchor_account" to null,
      "version" to 0,
    )

  private fun assertAcceptedRow(id: String, memo: String) {
    assertEquals(
      mapOf(
        "status" to "pending_user_transfer_start",
        "amount_in" to "20",
        "amount_out" to "19",
        "amount_expected" to "20",
        "amount_fee" to "1",
        "memo" to memo,
        "memo_type" to "id",
        "to_account" to OWN_DESTINATION,
        "withdraw_anchor_account" to OWN_DESTINATION,
        "version" to 1,
      ),
      row(id),
      "row of $id",
    )
  }

  private fun assertRejectedForDestination(response: RpcResponse) {
    assertNull(response.result)
    assertEquals(RpcErrorCode.INTERNAL_ERROR.errorCode, response.error.code)
    assertEquals("destination account is not the authenticated account", response.error.message)
  }

  private fun assertRejectedForMemoInUse(response: RpcResponse) {
    assertNull(response.result)
    assertEquals(RpcErrorCode.INVALID_PARAMS.errorCode, response.error.code)
    assertEquals(MEMO_IN_USE_MESSAGE, response.error.message)
  }

  private fun sep24Row(id: String): Map<String, Any?> =
    jdbc.queryForMap(
      """
      SELECT status, amount_in, amount_out, amount_expected, amount_fee, memo, memo_type,
             to_account, withdraw_anchor_account, version
      FROM sep24_transaction WHERE id = ?
      """,
      id,
    )

  private fun sep31Row(id: String): Map<String, Any?> =
    jdbc.queryForMap(
      """
      SELECT status, amount_in, amount_out, amount_expected, amount_fee, stellar_memo,
             stellar_memo_type, to_account, version
      FROM sep31_transaction WHERE id = ?
      """,
      id,
    )

  private fun untouchedSep24Row(amountIn: String, amountOut: String) =
    untouchedRow(amountIn, amountOut)

  private fun untouchedSep31Row(amountIn: String, amountOut: String): Map<String, Any?> =
    mapOf(
      "status" to "pending_receiver",
      "amount_in" to amountIn,
      "amount_out" to amountOut,
      "amount_expected" to amountIn,
      "amount_fee" to "1",
      "stellar_memo" to null,
      "stellar_memo_type" to null,
      "to_account" to null,
      "version" to 0,
    )

  // --- Scenarios --------------------------------------------------------------------------------

  /** The report: item 1 is rejected for its destination, item 2 commits (RPCB-02, RPCB-08). */
  @ParameterizedTest(name = "open-in-view bound = {0}")
  @ValueSource(booleans = [true, false])
  fun `a destination mismatch rejected before a committing item leaves the first row untouched`(
    openInView: Boolean
  ) {
    seedWithdrawal("target-txn-1", "10", "9")
    seedWithdrawal("benign-txn-2", "20", "19")

    val responses =
      handleBatch(
        openInView,
        rejectedForDestination(1, "target-txn-1"),
        accepted(2, "benign-txn-2", "888888"),
      )

    assertRejectedForDestination(responses[0])
    assertNotNull(responses[1].result)
    assertNull(responses[1].error)
    assertEquals(untouchedRow("10", "9"), row("target-txn-1"))
    assertAcceptedRow("benign-txn-2", "888888")
  }

  /** The report's first throw point: the memo is already pending elsewhere (RPCB-03, RPCB-08). */
  @ParameterizedTest(name = "open-in-view bound = {0}")
  @ValueSource(booleans = [true, false])
  fun `a memo in use rejected before a committing item leaves the first row untouched`(
    openInView: Boolean
  ) {
    seedWithdrawal("target-txn-1", "10", "9")
    seedWithdrawal("benign-txn-2", "20", "19")
    seedHolderOfBusyMemo()

    val responses =
      handleBatch(
        openInView,
        rejectedForMemoInUse(1, "target-txn-1"),
        accepted(2, "benign-txn-2", "888888"),
      )

    assertRejectedForMemoInUse(responses[0])
    assertNotNull(responses[1].result)
    assertEquals(untouchedRow("10", "9"), row("target-txn-1"))
    assertAcceptedRow("benign-txn-2", "888888")
  }

  /** Control: the reversed order (RPCB-09). */
  @ParameterizedTest(name = "open-in-view bound = {0}")
  @ValueSource(booleans = [true, false])
  fun `a committing item before a rejected one is saved and the rejected row is untouched`(
    openInView: Boolean
  ) {
    seedWithdrawal("target-txn-1", "10", "9")
    seedWithdrawal("benign-txn-2", "20", "19")

    val responses =
      handleBatch(
        openInView,
        accepted(1, "benign-txn-2", "888888"),
        rejectedForDestination(2, "target-txn-1"),
      )

    assertNotNull(responses[0].result)
    assertRejectedForDestination(responses[1])
    assertAcceptedRow("benign-txn-2", "888888")
    assertEquals(untouchedRow("10", "9"), row("target-txn-1"))
  }

  /** Control: the rejected item alone (RPCB-09). */
  @ParameterizedTest(name = "open-in-view bound = {0}")
  @ValueSource(booleans = [true, false])
  fun `a rejected item alone leaves its row untouched`(openInView: Boolean) {
    seedWithdrawal("target-txn-1", "10", "9")

    val responses = handleBatch(openInView, rejectedForDestination(1, "target-txn-1"))

    assertRejectedForDestination(responses.single())
    assertEquals(untouchedRow("10", "9"), row("target-txn-1"))
  }

  /** Control: two rejected items (RPCB-09). */
  @ParameterizedTest(name = "open-in-view bound = {0}")
  @ValueSource(booleans = [true, false])
  fun `two rejected items leave both rows untouched`(openInView: Boolean) {
    seedWithdrawal("target-txn-1", "10", "9")
    seedWithdrawal("target-txn-2", "30", "29")

    val responses =
      handleBatch(
        openInView,
        rejectedForDestination(1, "target-txn-1"),
        rejectedForDestination(2, "target-txn-2"),
      )

    responses.forEach { assertRejectedForDestination(it) }
    assertEquals(untouchedRow("10", "9"), row("target-txn-1"))
    assertEquals(untouchedRow("30", "29"), row("target-txn-2"))
  }

  /** Two accepted items commit both with their own values (RPCB-09). */
  @ParameterizedTest(name = "open-in-view bound = {0}")
  @ValueSource(booleans = [true, false])
  fun `two accepted items commit both transactions`(openInView: Boolean) {
    seedWithdrawal("benign-txn-1", "20", "19")
    seedWithdrawal("benign-txn-2", "20", "19")

    val responses =
      handleBatch(
        openInView,
        accepted(1, "benign-txn-1", "888881"),
        accepted(2, "benign-txn-2", "888882"),
      )

    responses.forEach {
      assertNotNull(it.result)
      assertNull(it.error)
    }
    assertAcceptedRow("benign-txn-1", "888881")
    assertAcceptedRow("benign-txn-2", "888882")
  }

  /** The sibling SEP-24 branch of the method has the same order of writes (RPCB-04). */
  @ParameterizedTest(name = "open-in-view bound = {0}")
  @ValueSource(booleans = [true, false])
  fun `a rejected SEP-24 item before a committing item leaves the first row untouched`(
    openInView: Boolean
  ) {
    seedSep24Withdrawal("target-txn-1", "10", "9")
    seedSep24Withdrawal("benign-txn-2", "20", "19")
    seedHolderOfBusyMemo()

    val responses =
      handleBatch(
        openInView,
        rejectedForMemoInUse(1, "target-txn-1"),
        accepted(2, "benign-txn-2", "888888"),
      )

    assertRejectedForMemoInUse(responses[0])
    assertNotNull(responses[1].result)
    assertEquals(untouchedSep24Row("10", "9"), sep24Row("target-txn-1"))
    assertEquals(
      mapOf(
        "status" to "pending_user_transfer_start",
        "amount_in" to "20",
        "amount_out" to "19",
        "amount_expected" to "20",
        "amount_fee" to "1",
        "memo" to "888888",
        "memo_type" to "id",
        "to_account" to OWN_DESTINATION,
        "withdraw_anchor_account" to OWN_DESTINATION,
        "version" to 1,
      ),
      sep24Row("benign-txn-2"),
    )
  }

  /** The sibling SEP-31 branch (RPCB-04). */
  @ParameterizedTest(name = "open-in-view bound = {0}")
  @ValueSource(booleans = [true, false])
  fun `a rejected SEP-31 item before a committing item leaves the first row untouched`(
    openInView: Boolean
  ) {
    seedSep31Receive("target-txn-1", "10", "9")
    seedSep31Receive("benign-txn-2", "20", "19")
    seedHolderOfBusyMemo()

    val responses =
      handleBatch(
        openInView,
        rejectedForMemoInUse(1, "target-txn-1"),
        accepted(2, "benign-txn-2", "888888"),
      )

    assertRejectedForMemoInUse(responses[0])
    assertNotNull(responses[1].result)
    assertEquals(untouchedSep31Row("10", "9"), sep31Row("target-txn-1"))
    assertEquals(
      mapOf(
        "status" to "pending_sender",
        "amount_in" to "20",
        "amount_out" to "19",
        "amount_expected" to "20",
        "amount_fee" to "1",
        "stellar_memo" to "888888",
        "stellar_memo_type" to "id",
        "to_account" to OWN_DESTINATION,
        "version" to 1,
      ),
      sep31Row("benign-txn-2"),
    )
  }

  /**
   * The failure ordering cannot cover (RPCB-05): the request is accepted, every field of the
   * transaction is already changed, and arming the observing account then fails. Only a request
   * without a shared persistence context keeps those changes out of the next item's commit, which
   * is how the Platform server runs (see DataConfigAdapterTest).
   */
  @Test
  fun `a failure while arming the destination leaves the first row untouched`() {
    seedWithdrawal("target-txn-1", "10", "9")
    seedWithdrawal("benign-txn-2", "20", "19")

    val responses =
      handleBatch(
        false,
        requestOnchainFunds(1, "target-txn-1", "500", "499", "777777", UNSTORABLE_DESTINATION),
        accepted(2, "benign-txn-2", "888888"),
      )

    assertNull(responses[0].result)
    assertEquals(RpcErrorCode.INTERNAL_ERROR.errorCode, responses[0].error.code)
    assertEquals("observing account could not be stored", responses[0].error.message)
    assertNotNull(responses[1].result)
    assertEquals(untouchedRow("10", "9"), row("target-txn-1"))
    assertAcceptedRow("benign-txn-2", "888888")
  }

  private fun getTransaction(rpcId: Int, transactionId: String) =
    RpcRequest.builder()
      .jsonrpc(JSON_RPC_VERSION)
      .id(rpcId)
      .method("get_transaction")
      .params(gson.fromJson("""{"transaction_id":"$transactionId"}""", Map::class.java))
      .build()

  /**
   * Edge case: the rejected item and the committing item are on the same transaction id. The second
   * item must apply on the stored row, not on a leftover of the first (RPCB-09).
   */
  @ParameterizedTest(name = "open-in-view bound = {0}")
  @ValueSource(booleans = [true, false])
  fun `an accepted item after a rejected one on the same transaction applies to the stored row`(
    openInView: Boolean
  ) {
    seedWithdrawal("target-txn-1", "10", "9")

    val responses =
      handleBatch(
        openInView,
        rejectedForDestination(1, "target-txn-1"),
        accepted(2, "target-txn-1", "888888"),
      )

    assertRejectedForDestination(responses[0])
    assertNotNull(responses[1].result)
    assertNull(responses[1].error)
    assertAcceptedRow("target-txn-1", "888888")
  }

  /** Edge case: a read after a rejected item in the same batch sees the unchanged row. */
  @ParameterizedTest(name = "open-in-view bound = {0}")
  @ValueSource(booleans = [true, false])
  fun `get_transaction after a rejected item returns the unchanged transaction`(
    openInView: Boolean
  ) {
    seedWithdrawal("target-txn-1", "10", "9")

    val responses =
      handleBatch(
        openInView,
        rejectedForDestination(1, "target-txn-1"),
        getTransaction(2, "target-txn-1"),
      )

    assertRejectedForDestination(responses[0])
    val read = responses[1].result as GetTransactionResponse
    assertEquals("10", read.amountIn.amount)
    assertEquals("9", read.amountOut.amount)
    assertEquals("pending_anchor", read.status.toString())
    assertEquals(untouchedRow("10", "9"), row("target-txn-1"))
  }
}
