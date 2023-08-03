package org.stellar.anchor.platform.action

import io.mockk.*
import io.mockk.impl.annotations.MockK
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.skyscreamer.jsonassert.JSONAssert
import org.skyscreamer.jsonassert.JSONCompareMode
import org.stellar.anchor.api.event.AnchorEvent
import org.stellar.anchor.api.event.AnchorEvent.Type.TRANSACTION_STATUS_CHANGED
import org.stellar.anchor.api.exception.rpc.InvalidParamsException
import org.stellar.anchor.api.exception.rpc.InvalidRequestException
import org.stellar.anchor.api.platform.GetTransactionResponse
import org.stellar.anchor.api.platform.PlatformTransactionData.Kind.DEPOSIT
import org.stellar.anchor.api.platform.PlatformTransactionData.Sep.SEP_24
import org.stellar.anchor.api.platform.PlatformTransactionData.Sep.SEP_38
import org.stellar.anchor.api.rpc.action.DoStellarPaymentRequest
import org.stellar.anchor.api.sep.SepTransactionStatus.*
import org.stellar.anchor.api.shared.Amount
import org.stellar.anchor.asset.AssetService
import org.stellar.anchor.config.CustodyConfig
import org.stellar.anchor.custody.CustodyService
import org.stellar.anchor.event.EventService
import org.stellar.anchor.event.EventService.EventQueue.TRANSACTION
import org.stellar.anchor.event.EventService.Session
import org.stellar.anchor.horizon.Horizon
import org.stellar.anchor.platform.data.JdbcSep24Transaction
import org.stellar.anchor.platform.data.JdbcTransactionPendingTrust
import org.stellar.anchor.platform.data.JdbcTransactionPendingTrustRepo
import org.stellar.anchor.platform.validator.RequestValidator
import org.stellar.anchor.sep24.Sep24TransactionStore
import org.stellar.anchor.sep31.Sep31TransactionStore
import org.stellar.anchor.util.GsonUtils

class DoStellarPaymentHandlerTest {

  companion object {
    private val gson = GsonUtils.getInstance()
    private const val TX_ID = "testId"
    private const val TO_ACCOUNT = "testToAccount"
    private const val AMOUNT_OUT_ASSET = "testAmountOutAsset"
    private const val VALIDATION_ERROR_MESSAGE = "Invalid request"
  }

  @MockK(relaxed = true) private lateinit var txn24Store: Sep24TransactionStore

  @MockK(relaxed = true) private lateinit var txn31Store: Sep31TransactionStore

  @MockK(relaxed = true) private lateinit var horizon: Horizon

  @MockK(relaxed = true) private lateinit var requestValidator: RequestValidator

  @MockK(relaxed = true) private lateinit var assetService: AssetService

  @MockK(relaxed = true) private lateinit var custodyConfig: CustodyConfig

  @MockK(relaxed = true) private lateinit var custodyService: CustodyService

  @MockK(relaxed = true) private lateinit var eventService: EventService

  @MockK(relaxed = true) private lateinit var eventSession: Session

  @MockK(relaxed = true)
  private lateinit var transactionPendingTrustRepo: JdbcTransactionPendingTrustRepo

  private lateinit var handler: DoStellarPaymentHandler

  @BeforeEach
  fun setup() {
    MockKAnnotations.init(this, relaxUnitFun = true)
    every { eventService.createSession(any(), TRANSACTION) } returns eventSession
    this.handler =
      DoStellarPaymentHandler(
        txn24Store,
        txn31Store,
        requestValidator,
        custodyConfig,
        horizon,
        assetService,
        custodyService,
        eventService,
        transactionPendingTrustRepo
      )
  }

  @Test
  fun test_handle_unsupportedProtocol() {
    val request = DoStellarPaymentRequest.builder().transactionId(TX_ID).build()
    val txn24 = JdbcSep24Transaction()
    txn24.status = PENDING_ANCHOR.toString()
    txn24.kind = DEPOSIT.kind
    txn24.transferReceivedAt = Instant.now()
    val spyTxn24 = spyk(txn24)

    every { txn24Store.findByTransactionId(TX_ID) } returns spyTxn24
    every { txn31Store.findByTransactionId(any()) } returns null
    every { spyTxn24.protocol } returns SEP_38.sep.toString()
    every { custodyConfig.isCustodyIntegrationEnabled } returns true

    val ex = assertThrows<InvalidRequestException> { handler.handle(request) }
    assertEquals(
      "Action[do_stellar_payment] is not supported. Status[pending_anchor], kind[null], protocol[38], funds received[true]",
      ex.message
    )
  }

  @Test
  fun test_handle_unsupportedStatus() {
    val request = DoStellarPaymentRequest.builder().transactionId(TX_ID).build()
    val txn24 = JdbcSep24Transaction()
    txn24.status = PENDING_TRUST.toString()
    txn24.kind = DEPOSIT.kind
    txn24.transferReceivedAt = Instant.now()

    every { txn24Store.findByTransactionId(TX_ID) } returns txn24
    every { txn31Store.findByTransactionId(any()) } returns null
    every { custodyConfig.isCustodyIntegrationEnabled } returns true

    val ex = assertThrows<InvalidRequestException> { handler.handle(request) }
    assertEquals(
      "Action[do_stellar_payment] is not supported. Status[pending_trust], kind[deposit], protocol[24], funds received[true]",
      ex.message
    )
  }

  @Test
  fun test_handle_handle_custodyIntegrationDisabled() {
    val request = DoStellarPaymentRequest.builder().transactionId(TX_ID).build()
    val txn24 = JdbcSep24Transaction()
    txn24.status = PENDING_ANCHOR.toString()
    txn24.kind = DEPOSIT.kind
    txn24.transferReceivedAt = Instant.now()

    every { txn24Store.findByTransactionId(TX_ID) } returns txn24
    every { txn31Store.findByTransactionId(any()) } returns null
    every { custodyConfig.isCustodyIntegrationEnabled } returns false

    val ex = assertThrows<InvalidRequestException> { handler.handle(request) }
    assertEquals("Action[do_stellar_payment] requires disabled custody integration", ex.message)
  }

  @Test
  fun test_handle_transferNotReceived() {
    val request = DoStellarPaymentRequest.builder().transactionId(TX_ID).build()
    val txn24 = JdbcSep24Transaction()
    txn24.status = PENDING_ANCHOR.toString()
    txn24.kind = DEPOSIT.kind

    every { txn24Store.findByTransactionId(TX_ID) } returns txn24
    every { txn31Store.findByTransactionId(any()) } returns null
    every { custodyConfig.isCustodyIntegrationEnabled } returns true

    val ex = assertThrows<InvalidRequestException> { handler.handle(request) }
    assertEquals(
      "Action[do_stellar_payment] is not supported. Status[pending_anchor], kind[deposit], protocol[24], funds received[false]",
      ex.message
    )
  }

  @Test
  fun test_handle_invalidRequest() {
    val request = DoStellarPaymentRequest.builder().transactionId(TX_ID).build()
    val txn24 = JdbcSep24Transaction()
    txn24.status = PENDING_ANCHOR.toString()
    txn24.kind = DEPOSIT.kind
    txn24.transferReceivedAt = Instant.now()

    every { txn24Store.findByTransactionId(TX_ID) } returns txn24
    every { txn31Store.findByTransactionId(any()) } returns null
    every { requestValidator.validate(request) } throws
      InvalidParamsException(VALIDATION_ERROR_MESSAGE)

    val ex = assertThrows<InvalidParamsException> { handler.handle(request) }
    assertEquals(VALIDATION_ERROR_MESSAGE, ex.message?.trimIndent())
  }

  @Test
  fun test_handle_ok_trustlineConfigured() {
    val transferReceivedAt = Instant.now()
    val request = DoStellarPaymentRequest.builder().transactionId(TX_ID).build()
    val txn24 = JdbcSep24Transaction()
    txn24.id = TX_ID
    txn24.status = PENDING_ANCHOR.toString()
    txn24.kind = DEPOSIT.kind
    txn24.transferReceivedAt = transferReceivedAt
    txn24.toAccount = TO_ACCOUNT
    txn24.amountOutAsset = AMOUNT_OUT_ASSET
    val sep24TxnCapture = slot<JdbcSep24Transaction>()
    val anchorEventCapture = slot<AnchorEvent>()

    every { txn24Store.findByTransactionId(TX_ID) } returns txn24
    every { txn31Store.findByTransactionId(any()) } returns null
    every { txn24Store.save(capture(sep24TxnCapture)) } returns null
    every { custodyConfig.isCustodyIntegrationEnabled } returns true
    every { horizon.isTrustlineConfigured(TO_ACCOUNT, AMOUNT_OUT_ASSET) } returns true
    every { eventSession.publish(capture(anchorEventCapture)) } just Runs

    val startDate = Instant.now()
    val response = handler.handle(request)
    val endDate = Instant.now()

    verify(exactly = 0) { txn31Store.save(any()) }
    verify(exactly = 0) { transactionPendingTrustRepo.save(any()) }
    verify(exactly = 1) { custodyService.createTransactionPayment(TX_ID, null) }

    val expectedSep24Txn = JdbcSep24Transaction()
    expectedSep24Txn.id = TX_ID
    expectedSep24Txn.kind = DEPOSIT.kind
    expectedSep24Txn.status = PENDING_STELLAR.toString()
    expectedSep24Txn.updatedAt = sep24TxnCapture.captured.updatedAt
    expectedSep24Txn.transferReceivedAt = transferReceivedAt
    expectedSep24Txn.toAccount = TO_ACCOUNT
    expectedSep24Txn.amountOutAsset = AMOUNT_OUT_ASSET

    JSONAssert.assertEquals(
      gson.toJson(expectedSep24Txn),
      gson.toJson(sep24TxnCapture.captured),
      JSONCompareMode.STRICT
    )

    val expectedResponse = GetTransactionResponse()
    expectedResponse.id = TX_ID
    expectedResponse.sep = SEP_24
    expectedResponse.kind = DEPOSIT
    expectedResponse.status = PENDING_STELLAR
    expectedResponse.amountExpected = Amount(null, "")
    expectedResponse.updatedAt = sep24TxnCapture.captured.updatedAt
    expectedResponse.destinationAccount = TO_ACCOUNT

    JSONAssert.assertEquals(
      gson.toJson(expectedResponse),
      gson.toJson(response),
      JSONCompareMode.STRICT
    )

    val expectedEvent =
      AnchorEvent.builder()
        .id(anchorEventCapture.captured.id)
        .sep(SEP_24.sep.toString())
        .type(TRANSACTION_STATUS_CHANGED)
        .transaction(expectedResponse)
        .build()

    JSONAssert.assertEquals(
      gson.toJson(expectedEvent),
      gson.toJson(anchorEventCapture.captured),
      JSONCompareMode.STRICT
    )

    assertTrue(sep24TxnCapture.captured.updatedAt >= startDate)
    assertTrue(sep24TxnCapture.captured.updatedAt <= endDate)
  }

  @Test
  fun test_handle_ok_trustlineNotConfigured() {
    val transferReceivedAt = Instant.now()
    val request = DoStellarPaymentRequest.builder().transactionId(TX_ID).build()
    val txn24 = JdbcSep24Transaction()
    txn24.id = TX_ID
    txn24.status = PENDING_ANCHOR.toString()
    txn24.kind = DEPOSIT.kind
    txn24.transferReceivedAt = transferReceivedAt
    txn24.toAccount = TO_ACCOUNT
    txn24.amountOutAsset = AMOUNT_OUT_ASSET
    val sep24TxnCapture = slot<JdbcSep24Transaction>()
    val txnPendingTrustCapture = slot<JdbcTransactionPendingTrust>()
    val anchorEventCapture = slot<AnchorEvent>()

    every { txn24Store.findByTransactionId(TX_ID) } returns txn24
    every { txn31Store.findByTransactionId(any()) } returns null
    every { txn24Store.save(capture(sep24TxnCapture)) } returns null
    every { custodyConfig.isCustodyIntegrationEnabled } returns true
    every { horizon.isTrustlineConfigured(TO_ACCOUNT, AMOUNT_OUT_ASSET) } returns false
    every { transactionPendingTrustRepo.save(capture(txnPendingTrustCapture)) } returns null
    every { eventSession.publish(capture(anchorEventCapture)) } just Runs

    val startDate = Instant.now()
    val response = handler.handle(request)
    val endDate = Instant.now()

    verify(exactly = 0) { txn31Store.save(any()) }
    verify(exactly = 0) { custodyService.createTransactionPayment(any(), any()) }

    val expectedSep24Txn = JdbcSep24Transaction()
    expectedSep24Txn.id = TX_ID
    expectedSep24Txn.kind = DEPOSIT.kind
    expectedSep24Txn.status = PENDING_TRUST.toString()
    expectedSep24Txn.updatedAt = sep24TxnCapture.captured.updatedAt
    expectedSep24Txn.transferReceivedAt = transferReceivedAt
    expectedSep24Txn.toAccount = TO_ACCOUNT
    expectedSep24Txn.amountOutAsset = AMOUNT_OUT_ASSET

    JSONAssert.assertEquals(
      gson.toJson(expectedSep24Txn),
      gson.toJson(sep24TxnCapture.captured),
      JSONCompareMode.STRICT
    )

    val expectedResponse = GetTransactionResponse()
    expectedResponse.id = TX_ID
    expectedResponse.sep = SEP_24
    expectedResponse.kind = DEPOSIT
    expectedResponse.status = PENDING_TRUST
    expectedResponse.amountExpected = Amount(null, "")
    expectedResponse.updatedAt = sep24TxnCapture.captured.updatedAt
    expectedResponse.destinationAccount = TO_ACCOUNT

    JSONAssert.assertEquals(
      gson.toJson(expectedResponse),
      gson.toJson(response),
      JSONCompareMode.STRICT
    )

    val expectedEvent =
      AnchorEvent.builder()
        .id(anchorEventCapture.captured.id)
        .sep(SEP_24.sep.toString())
        .type(TRANSACTION_STATUS_CHANGED)
        .transaction(expectedResponse)
        .build()

    JSONAssert.assertEquals(
      gson.toJson(expectedEvent),
      gson.toJson(anchorEventCapture.captured),
      JSONCompareMode.STRICT
    )

    val expectedTxnPendingTrust = JdbcTransactionPendingTrust()
    expectedTxnPendingTrust.id = TX_ID
    expectedTxnPendingTrust.asset = AMOUNT_OUT_ASSET
    expectedTxnPendingTrust.account = TO_ACCOUNT
    expectedTxnPendingTrust.createdAt = txnPendingTrustCapture.captured.createdAt

    JSONAssert.assertEquals(
      gson.toJson(expectedTxnPendingTrust),
      gson.toJson(txnPendingTrustCapture.captured),
      JSONCompareMode.STRICT
    )

    assertTrue(sep24TxnCapture.captured.updatedAt >= startDate)
    assertTrue(sep24TxnCapture.captured.updatedAt <= endDate)
    assertTrue(txnPendingTrustCapture.captured.createdAt >= startDate)
    assertTrue(txnPendingTrustCapture.captured.createdAt <= endDate)
  }
}
