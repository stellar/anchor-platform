package org.stellar.anchor.platform.rpc

import io.micrometer.core.instrument.Counter
import io.mockk.*
import io.mockk.impl.annotations.MockK
import jakarta.validation.Validation
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import org.stellar.anchor.api.event.AnchorEvent
import org.stellar.anchor.api.platform.PlatformTransactionData.Kind.DEPOSIT
import org.stellar.anchor.api.platform.PlatformTransactionData.Kind.WITHDRAWAL
import org.stellar.anchor.api.rpc.RpcRequest
import org.stellar.anchor.api.rpc.RpcResponse
import org.stellar.anchor.api.sep.SepTransactionStatus
import org.stellar.anchor.api.sep.SepTransactionStatus.*
import org.stellar.anchor.asset.DefaultAssetService
import org.stellar.anchor.event.EventService
import org.stellar.anchor.event.EventService.EventQueue.TRANSACTION
import org.stellar.anchor.event.EventService.Session
import org.stellar.anchor.metrics.MetricsService
import org.stellar.anchor.platform.config.RpcConfig
import org.stellar.anchor.platform.data.*
import org.stellar.anchor.platform.service.RpcService
import org.stellar.anchor.platform.utils.RpcUtil.JSON_RPC_VERSION
import org.stellar.anchor.platform.validator.RequestValidator
import org.stellar.anchor.sep24.Sep24TransactionStore
import org.stellar.anchor.sep31.Sep31TransactionStore
import org.stellar.anchor.sep6.Sep6TransactionStore

/**
 * HackerOne #4194108 (ANCHOR-1530): a `refund` object supplied to notify_refund_sent or
 * notify_refund_pending is validated field by field. Every test goes through [RpcService], the real
 * handlers and a real Jakarta `Validator` (the handler tests elsewhere mock RequestValidator, so
 * they cannot see a dropped `@Valid`). Test names start with the spec requirement id (refvNN).
 */
class RefundNestedValidationTest {
  companion object {
    private const val TX_ID = "testId"
    private const val NATIVE = "stellar:native"
    private const val SENT = "notify_refund_sent"
    private const val PENDING = "notify_refund_pending"
  }

  @MockK(relaxed = true) private lateinit var txn6Store: Sep6TransactionStore
  @MockK(relaxed = true) private lateinit var txn24Store: Sep24TransactionStore
  @MockK(relaxed = true) private lateinit var txn31Store: Sep31TransactionStore
  @MockK(relaxed = true) private lateinit var eventService: EventService
  @MockK(relaxed = true) private lateinit var metricsService: MetricsService
  @MockK(relaxed = true) private lateinit var eventSession: Session
  @MockK(relaxed = true) private lateinit var counter: Counter
  @MockK(relaxed = true) private lateinit var rpcConfig: RpcConfig

  private lateinit var rpcService: RpcService

  @BeforeEach
  fun setup() {
    MockKAnnotations.init(this, relaxUnitFun = true)
    every { rpcConfig.batchSizeLimit } returns 10
    every { eventService.createSession(any(), TRANSACTION) } returns eventSession
    every { eventSession.publish(any<AnchorEvent>()) } just Runs
    every { metricsService.counter(any(), any(), any()) } returns counter
    every { txn6Store.findByTransactionId(any()) } returns null
    every { txn24Store.findByTransactionId(any()) } returns null
    every { txn31Store.findByTransactionId(any()) } returns null
    val assetService = DefaultAssetService.fromJsonResource("test_assets_native_7_decimals.json")
    val requestValidator = RequestValidator(Validation.buildDefaultValidatorFactory().validator)
    rpcService =
      RpcService(
        listOf(
          NotifyRefundSentHandler(
            txn6Store,
            txn24Store,
            txn31Store,
            requestValidator,
            assetService,
            eventService,
            metricsService
          ),
          NotifyRefundPendingHandler(
            txn6Store,
            txn24Store,
            txn31Store,
            requestValidator,
            assetService,
            eventService,
            metricsService
          )
        ),
        rpcConfig
      )
  }

  private fun amount(amount: String?, asset: String? = NATIVE): Map<String, Any?> =
    mapOf("amount" to amount, "asset" to asset)

  private fun refund(
    id: String? = "A",
    amount: Map<String, Any?>? = amount("40"),
    amountFee: Map<String, Any?>? = amount("0")
  ): Map<String, Any?> = mapOf("id" to id, "amount" to amount, "amount_fee" to amountFee)

  private fun call(method: String, refund: Map<String, Any?>?): RpcResponse {
    val params = mutableMapOf<String, Any?>("transaction_id" to TX_ID)
    if (refund != null) params["refund"] = refund
    val response =
      rpcService.handle(
        listOf(
          RpcRequest.builder().jsonrpc(JSON_RPC_VERSION).id(1).method(method).params(params).build()
        )
      )
    assertEquals(1, response.size)
    return response[0]
  }

  private fun sep6Txn(
    kind: String = DEPOSIT.kind,
    status: SepTransactionStatus = PENDING_ANCHOR
  ): JdbcSep6Transaction {
    val txn = JdbcSep6Transaction()
    txn.status = status.toString()
    txn.kind = kind
    txn.transferReceivedAt = Instant.now()
    txn.amountIn = "100"
    txn.amountInAsset = NATIVE
    txn.amountFee = "0"
    txn.amountFeeAsset = NATIVE
    every { txn6Store.findByTransactionId(TX_ID) } returns txn
    return txn
  }

  private fun sep24Txn(
    kind: String = DEPOSIT.kind,
    status: SepTransactionStatus = PENDING_ANCHOR
  ): JdbcSep24Transaction {
    val txn = JdbcSep24Transaction()
    txn.status = status.toString()
    txn.kind = kind
    txn.transferReceivedAt = Instant.now()
    txn.amountIn = "100"
    txn.amountInAsset = NATIVE
    txn.amountFee = "0"
    txn.amountFeeAsset = NATIVE
    every { txn24Store.findByTransactionId(TX_ID) } returns txn
    return txn
  }

  private fun sep31Txn(): JdbcSep31Transaction {
    val txn = JdbcSep31Transaction()
    txn.status = PENDING_RECEIVER.toString()
    txn.transferReceivedAt = Instant.now()
    txn.amountIn = "100"
    txn.amountInAsset = NATIVE
    txn.amountFee = "0"
    txn.amountFeeAsset = NATIVE
    every { txn31Store.findByTransactionId(TX_ID) } returns txn
    return txn
  }

  private fun assertInvalidParams(response: RpcResponse) {
    assertNull(response.result)
    assertNotNull(response.error)
    assertEquals(-32602, response.error.code)
  }

  /** The malformed refund shapes of REFV-01 and REFV-02, by name. */
  private fun malformed(shape: String): Map<String, Any?> =
    when (shape) {
      "no-id" -> refund(id = null)
      "empty-id" -> refund(id = "")
      "blank-id" -> refund(id = "   ")
      "no-amount" -> refund(amount = null)
      "no-amount-fee" -> refund(amountFee = null)
      "no-amount-value" -> refund(amount = amount(null))
      "blank-amount-value" -> refund(amount = amount(" "))
      "no-amount-asset" -> refund(amount = amount("40", null))
      "no-amount-fee-value" -> refund(amountFee = amount(null))
      "no-amount-fee-asset" -> refund(amountFee = amount("0", null))
      "blank-amount-asset" -> refund(amount = amount("40", " "))
      "blank-amount-fee-value" -> refund(amountFee = amount(" "))
      "blank-amount-fee-asset" -> refund(amountFee = amount("0", " "))
      "empty-object" -> emptyMap()
      else -> throw IllegalArgumentException(shape)
    }

  // ---- REFV-01, REFV-02, REFV-03: rejected as -32602, nothing stored ----

  @ParameterizedTest
  @CsvSource(
    "notify_refund_sent,no-id",
    "notify_refund_sent,empty-id",
    "notify_refund_sent,blank-id",
    "notify_refund_sent,no-amount",
    "notify_refund_sent,no-amount-fee",
    "notify_refund_sent,no-amount-value",
    "notify_refund_sent,blank-amount-value",
    "notify_refund_sent,no-amount-asset",
    "notify_refund_sent,no-amount-fee-value",
    "notify_refund_sent,no-amount-fee-asset",
    "notify_refund_sent,blank-amount-asset",
    "notify_refund_sent,blank-amount-fee-value",
    "notify_refund_sent,blank-amount-fee-asset",
    "notify_refund_sent,empty-object",
    "notify_refund_pending,no-id",
    "notify_refund_pending,empty-id",
    "notify_refund_pending,blank-id",
    "notify_refund_pending,no-amount",
    "notify_refund_pending,no-amount-fee",
    "notify_refund_pending,no-amount-value",
    "notify_refund_pending,blank-amount-value",
    "notify_refund_pending,no-amount-asset",
    "notify_refund_pending,no-amount-fee-value",
    "notify_refund_pending,no-amount-fee-asset",
    "notify_refund_pending,blank-amount-asset",
    "notify_refund_pending,blank-amount-fee-value",
    "notify_refund_pending,blank-amount-fee-asset",
    "notify_refund_pending,empty-object"
  )
  fun refv01_02_03_sep6_malformedRefundIsRejectedAsInvalidParamsAndNothingIsStored(
    method: String,
    shape: String
  ) {
    val txn = sep6Txn()

    val response = call(method, malformed(shape))

    assertInvalidParams(response)
    assertNull(txn.refunds)
    assertEquals(PENDING_ANCHOR.toString(), txn.status)
    verify(exactly = 0) { txn6Store.save(any()) }
  }

  @ParameterizedTest
  @CsvSource(
    "notify_refund_sent,no-id",
    "notify_refund_sent,blank-id",
    "notify_refund_sent,no-amount",
    "notify_refund_sent,no-amount-fee",
    "notify_refund_pending,no-id",
    "notify_refund_pending,blank-id",
    "notify_refund_pending,no-amount",
    "notify_refund_pending,no-amount-fee"
  )
  fun refv01_02_03_sep24_malformedRefundIsRejectedAsInvalidParamsAndNothingIsStored(
    method: String,
    shape: String
  ) {
    val txn = sep24Txn()

    val response = call(method, malformed(shape))

    assertInvalidParams(response)
    assertNull(txn.refunds)
    assertEquals(PENDING_ANCHOR.toString(), txn.status)
    verify(exactly = 0) { txn24Store.save(any()) }
  }

  @ParameterizedTest
  @ValueSource(strings = ["no-id", "blank-id", "no-amount", "no-amount-fee"])
  fun refv01_02_03_sep31_sentWithMalformedRefundIsRejectedAsInvalidParamsAndNothingIsStored(
    shape: String
  ) {
    val txn = sep31Txn()

    val response = call(SENT, malformed(shape))

    assertInvalidParams(response)
    assertNull(txn.refunds)
    assertEquals(PENDING_RECEIVER.toString(), txn.status)
    verify(exactly = 0) { txn31Store.save(any()) }
  }

  // ---- REFV-04: optional refund object and valid refunds keep working ----

  @Test
  fun refv04_sep6_sentWithoutRefundOutsidePendingAnchorIsAccepted() {
    val txn = sep6Txn(status = PENDING_EXTERNAL)
    assertNull(call(SENT, refund()).error) // stores refund A, back to pending_anchor
    txn.status = PENDING_EXTERNAL.toString()

    val response = call(SENT, null)

    assertNull(response.error)
    assertEquals(listOf("A"), txn.refunds.payments.map { it.id })
  }

  @Test
  fun refv04_sep6_pendingWithoutRefundOnWithdrawalIsAccepted() {
    val txn = sep6Txn(kind = WITHDRAWAL.kind, status = PENDING_EXTERNAL)

    val response = call(PENDING, null)

    assertNull(response.error)
    assertEquals(PENDING_ANCHOR.toString(), txn.status)
  }

  @Test
  fun refv04_sep24_sentWithoutRefundOutsidePendingAnchorIsAccepted() {
    val txn = sep24Txn(status = PENDING_EXTERNAL)
    assertNull(call(SENT, refund()).error) // stores refund A, back to pending_anchor
    txn.status = PENDING_EXTERNAL.toString()

    val response = call(SENT, null)

    assertNull(response.error)
    assertEquals(listOf("A"), txn.refunds.refundPayments.map { it.id })
  }

  @Test
  fun refv04_sep24_pendingWithoutRefundOnWithdrawalIsAccepted() {
    val txn = sep24Txn(kind = WITHDRAWAL.kind, status = PENDING_EXTERNAL)

    val response = call(PENDING, null)

    assertNull(response.error)
    assertEquals(PENDING_ANCHOR.toString(), txn.status)
  }

  @ParameterizedTest
  @ValueSource(strings = ["no-id", "blank-id", "no-amount", "no-amount-fee"])
  fun refv01_02_sep6_pendingWithMalformedRefundOnWithdrawalIsRejectedAndUnchanged(shape: String) {
    val txn = sep6Txn(kind = WITHDRAWAL.kind, status = PENDING_EXTERNAL)

    val response = call(PENDING, malformed(shape))

    assertInvalidParams(response)
    assertNull(txn.refunds)
    assertEquals(PENDING_EXTERNAL.toString(), txn.status)
    verify(exactly = 0) { txn6Store.save(any()) }
  }

  @ParameterizedTest
  @ValueSource(strings = ["blank-amount-value", "no-amount-asset", "blank-amount-fee-value"])
  fun refv02_sep6_pendingOnWithdrawalNeverReadsRefundAmountsAndStoresNothing(shape: String) {
    val txn = sep6Txn(kind = WITHDRAWAL.kind, status = PENDING_EXTERNAL)

    val response = call(PENDING, malformed(shape))

    assertNull(response.error)
    assertNull(txn.refunds)
    assertEquals(PENDING_ANCHOR.toString(), txn.status)
  }

  // ---- Edge: the status guard answers before the refund is looked at ----

  @ParameterizedTest
  @ValueSource(strings = [SENT, PENDING])
  fun edge_sep6_malformedRefundOnUnsupportedStatusAnswersInvalidRequestAndLeavesTheTransaction(
    method: String
  ) {
    val txn = sep6Txn(status = COMPLETED)

    val response = call(method, refund(id = null))

    assertNull(response.result)
    assertEquals(-32600, response.error.code)
    assertNull(txn.refunds)
    assertEquals(COMPLETED.toString(), txn.status)
    verify(exactly = 0) { txn6Store.save(any()) }
  }

  @ParameterizedTest
  @ValueSource(strings = [SENT, PENDING])
  fun refv04_sep6_validRefundWithZeroFeeIsStored(method: String) {
    val txn = sep6Txn()

    val response = call(method, refund(id = "A", amount = amount("40"), amountFee = amount("0")))

    assertNull(response.error)
    assertEquals(listOf("A"), txn.refunds.payments.map { it.id })
    assertEquals("40", txn.refunds.amountRefunded.amount)
  }

  @ParameterizedTest
  @ValueSource(strings = [SENT, PENDING])
  fun refv04_sep24_validRefundWithZeroFeeIsStored(method: String) {
    val txn = sep24Txn()

    val response = call(method, refund(id = "A", amount = amount("40"), amountFee = amount("0")))

    assertNull(response.error)
    assertEquals(listOf("A"), txn.refunds.refundPayments.map { it.id })
    assertEquals("40", txn.refunds.amountRefunded)
  }

  @Test
  fun refv04_sep31_sentWithValidRefundIsStored() {
    val txn = sep31Txn()

    val response = call(SENT, refund())

    assertNull(response.error)
    assertEquals(listOf("A"), txn.refunds.refundPayments.map { it.id })
  }

  @ParameterizedTest
  @ValueSource(strings = [SENT, PENDING])
  fun refv04_sep6_validRefundAfterRejectedOneIsTheFirstStoredPayment(method: String) {
    val txn = sep6Txn()
    assertInvalidParams(call(method, refund(id = null)))

    val response = call(method, refund(id = "B", amount = amount("30")))

    assertNull(response.error)
    assertEquals(listOf("B"), txn.refunds.payments.map { it.id })
    assertEquals("30", txn.refunds.amountRefunded.amount)
  }

  @ParameterizedTest
  @ValueSource(strings = [SENT, PENDING])
  fun refv04_sep24_validRefundAfterRejectedOneIsTheFirstStoredPayment(method: String) {
    val txn = sep24Txn()
    assertInvalidParams(call(method, refund(id = null)))

    val response = call(method, refund(id = "B", amount = amount("30")))

    assertNull(response.error)
    assertEquals(listOf("B"), txn.refunds.refundPayments.map { it.id })
    assertEquals("30", txn.refunds.amountRefunded)
  }

  // ---- REFV-03: the rejected item reports -32602 inside a batch ----

  @Test
  fun refv03_rejectedItemInABatchAnswersInvalidParamsWhileTheOtherSucceeds() {
    val txn = sep6Txn()
    fun request(id: Int, refund: Map<String, Any?>) =
      RpcRequest.builder()
        .jsonrpc(JSON_RPC_VERSION)
        .id(id)
        .method(SENT)
        .params(mapOf("transaction_id" to TX_ID, "refund" to refund))
        .build()

    val response = rpcService.handle(listOf(request(1, refund(id = null)), request(2, refund("B"))))

    assertInvalidParams(response[0])
    assertNull(response[1].error)
    assertEquals(listOf("B"), txn.refunds.payments.map { it.id })
  }
}
