package org.stellar.anchor.platform.rpc

import com.google.gson.reflect.TypeToken
import io.micrometer.core.instrument.Counter
import io.mockk.*
import io.mockk.impl.annotations.MockK
import java.io.IOException
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
import org.stellar.anchor.api.exception.rpc.InternalErrorException
import org.stellar.anchor.api.exception.rpc.InvalidParamsException
import org.stellar.anchor.api.exception.rpc.InvalidRequestException
import org.stellar.anchor.api.platform.GetTransactionResponse
import org.stellar.anchor.api.platform.PlatformTransactionData.Kind.*
import org.stellar.anchor.api.platform.PlatformTransactionData.Sep.SEP_24
import org.stellar.anchor.api.platform.PlatformTransactionData.Sep.SEP_38
import org.stellar.anchor.api.rpc.method.NotifyOnchainFundsSentRequest
import org.stellar.anchor.api.sep.SepTransactionStatus.*
import org.stellar.anchor.api.shared.Amount
import org.stellar.anchor.api.shared.StellarTransaction
import org.stellar.anchor.asset.AssetService
import org.stellar.anchor.event.EventService
import org.stellar.anchor.event.EventService.EventQueue.TRANSACTION
import org.stellar.anchor.event.EventService.Session
import org.stellar.anchor.horizon.Horizon
import org.stellar.anchor.metrics.MetricsService
import org.stellar.anchor.platform.data.JdbcSep24Transaction
import org.stellar.anchor.platform.service.AnchorMetrics.PLATFORM_RPC_TRANSACTION
import org.stellar.anchor.platform.validator.RequestValidator
import org.stellar.anchor.sep24.Sep24TransactionStore
import org.stellar.anchor.sep31.Sep31TransactionStore
import org.stellar.anchor.util.GsonUtils
import org.stellar.sdk.responses.operations.OperationResponse
import org.stellar.sdk.responses.operations.PaymentOperationResponse

class NotifyOnchainFundsSentHandlerTest {

  companion object {
    private val gson = GsonUtils.getInstance()
    private const val TX_ID = "testId"
    private const val STELLAR_TX_ID = "stellarTxId"
    private const val VALIDATION_ERROR_MESSAGE = "Invalid request"
  }

  @MockK(relaxed = true) private lateinit var txn24Store: Sep24TransactionStore

  @MockK(relaxed = true) private lateinit var txn31Store: Sep31TransactionStore

  @MockK(relaxed = true) private lateinit var requestValidator: RequestValidator

  @MockK(relaxed = true) private lateinit var assetService: AssetService

  @MockK(relaxed = true) private lateinit var horizon: Horizon

  @MockK(relaxed = true) private lateinit var eventService: EventService

  @MockK(relaxed = true) private lateinit var metricsService: MetricsService

  @MockK(relaxed = true) private lateinit var eventSession: Session

  @MockK(relaxed = true) private lateinit var sepTransactionCounter: Counter

  private lateinit var handler: NotifyOnchainFundsSentHandler

  @BeforeEach
  fun setup() {
    MockKAnnotations.init(this, relaxUnitFun = true)
    every { eventService.createSession(any(), TRANSACTION) } returns eventSession
    this.handler =
      NotifyOnchainFundsSentHandler(
        txn24Store,
        txn31Store,
        requestValidator,
        horizon,
        assetService,
        eventService,
        metricsService
      )
  }

  @Test
  fun test_handle_unsupportedProtocol() {
    val request = NotifyOnchainFundsSentRequest.builder().transactionId(TX_ID).build()
    val txn24 = JdbcSep24Transaction()
    txn24.status = PENDING_STELLAR.toString()
    val spyTxn24 = spyk(txn24)

    every { txn24Store.findByTransactionId(TX_ID) } returns spyTxn24
    every { txn31Store.findByTransactionId(any()) } returns null
    every { spyTxn24.protocol } returns SEP_38.sep.toString()

    val ex = assertThrows<InvalidRequestException> { handler.handle(request) }
    assertEquals(
      "RPC method[notify_onchain_funds_sent] is not supported. Status[pending_stellar], kind[null], protocol[38], funds received[false]",
      ex.message
    )

    verify(exactly = 0) { txn24Store.save(any()) }
    verify(exactly = 0) { txn31Store.save(any()) }
    verify(exactly = 0) { sepTransactionCounter.increment() }
  }

  @Test
  fun test_handle_unsupportedStatus() {
    val request = NotifyOnchainFundsSentRequest.builder().transactionId(TX_ID).build()
    val txn24 = JdbcSep24Transaction()
    txn24.status = INCOMPLETE.toString()
    txn24.kind = DEPOSIT.kind

    every { txn24Store.findByTransactionId(TX_ID) } returns txn24
    every { txn31Store.findByTransactionId(any()) } returns null

    val ex = assertThrows<InvalidRequestException> { handler.handle(request) }
    assertEquals(
      "RPC method[notify_onchain_funds_sent] is not supported. Status[incomplete], kind[deposit], protocol[24], funds received[false]",
      ex.message
    )

    verify(exactly = 0) { txn24Store.save(any()) }
    verify(exactly = 0) { txn31Store.save(any()) }
    verify(exactly = 0) { sepTransactionCounter.increment() }
  }

  @Test
  fun test_handle_unsupportedKind() {
    val request = NotifyOnchainFundsSentRequest.builder().transactionId(TX_ID).build()
    val txn24 = JdbcSep24Transaction()
    txn24.status = PENDING_STELLAR.toString()
    txn24.kind = WITHDRAWAL.kind

    every { txn24Store.findByTransactionId(TX_ID) } returns txn24
    every { txn31Store.findByTransactionId(any()) } returns null

    val ex = assertThrows<InvalidRequestException> { handler.handle(request) }
    assertEquals(
      "RPC method[notify_onchain_funds_sent] is not supported. Status[pending_stellar], kind[withdrawal], protocol[24], funds received[false]",
      ex.message
    )

    verify(exactly = 0) { txn24Store.save(any()) }
    verify(exactly = 0) { txn31Store.save(any()) }
    verify(exactly = 0) { sepTransactionCounter.increment() }
  }

  @Test
  fun test_handle_transferNotReceived() {
    val request = NotifyOnchainFundsSentRequest.builder().transactionId(TX_ID).build()
    val txn24 = JdbcSep24Transaction()
    txn24.status = PENDING_ANCHOR.toString()
    txn24.kind = WITHDRAWAL.kind

    every { txn24Store.findByTransactionId(TX_ID) } returns txn24
    every { txn31Store.findByTransactionId(any()) } returns null

    val ex = assertThrows<InvalidRequestException> { handler.handle(request) }
    assertEquals(
      "RPC method[notify_onchain_funds_sent] is not supported. Status[pending_anchor], kind[withdrawal], protocol[24], funds received[false]",
      ex.message
    )

    verify(exactly = 0) { txn24Store.save(any()) }
    verify(exactly = 0) { txn31Store.save(any()) }
    verify(exactly = 0) { sepTransactionCounter.increment() }
  }

  @Test
  fun test_handle_invalidRequest() {
    val transferReceivedAt = Instant.now()
    val request = NotifyOnchainFundsSentRequest.builder().transactionId(TX_ID).build()
    val txn24 = JdbcSep24Transaction()
    txn24.status = PENDING_ANCHOR.toString()
    txn24.kind = DEPOSIT.kind
    txn24.transferReceivedAt = transferReceivedAt

    every { txn24Store.findByTransactionId(TX_ID) } returns txn24
    every { txn31Store.findByTransactionId(any()) } returns null
    every { requestValidator.validate(request) } throws
      InvalidParamsException(VALIDATION_ERROR_MESSAGE)

    val ex = assertThrows<InvalidParamsException> { handler.handle(request) }
    assertEquals(VALIDATION_ERROR_MESSAGE, ex.message?.trimIndent())

    verify(exactly = 0) { txn24Store.save(any()) }
    verify(exactly = 0) { txn31Store.save(any()) }
    verify(exactly = 0) { sepTransactionCounter.increment() }
  }

  @Test
  fun test_handle_ok() {
    val transferReceivedAt = Instant.now()
    val request =
      NotifyOnchainFundsSentRequest.builder()
        .transactionId(TX_ID)
        .stellarTransactionId(STELLAR_TX_ID)
        .build()
    val txn24 = JdbcSep24Transaction()
    txn24.status = PENDING_ANCHOR.toString()
    txn24.kind = DEPOSIT.kind
    txn24.transferReceivedAt = transferReceivedAt
    val sep24TxnCapture = slot<JdbcSep24Transaction>()
    val anchorEventCapture = slot<AnchorEvent>()

    val operationRecordsTypeToken =
      object : TypeToken<ArrayList<PaymentOperationResponse>>() {}.type
    val operationRecords: ArrayList<OperationResponse> =
      gson.fromJson(paymentOperationRecord, operationRecordsTypeToken)

    val stellarTransactionsToken = object : TypeToken<List<StellarTransaction>>() {}.type
    val stellarTransactions: List<StellarTransaction> =
      gson.fromJson(stellarTransactions, stellarTransactionsToken)

    every { txn24Store.findByTransactionId(TX_ID) } returns txn24
    every { txn31Store.findByTransactionId(any()) } returns null
    every { txn24Store.save(capture(sep24TxnCapture)) } returns null
    every { horizon.getStellarTxnOperations(STELLAR_TX_ID) } returns operationRecords
    every { eventSession.publish(capture(anchorEventCapture)) } just Runs
    every { metricsService.counter(PLATFORM_RPC_TRANSACTION, "SEP", "sep24") } returns
      sepTransactionCounter

    val startDate = Instant.now()
    val response = handler.handle(request)
    val endDate = Instant.now()

    verify(exactly = 0) { txn31Store.save(any()) }
    verify(exactly = 1) { sepTransactionCounter.increment() }

    val expectedSep24Txn = JdbcSep24Transaction()
    expectedSep24Txn.kind = DEPOSIT.kind
    expectedSep24Txn.status = COMPLETED.toString()
    expectedSep24Txn.updatedAt = sep24TxnCapture.captured.updatedAt
    expectedSep24Txn.completedAt = sep24TxnCapture.captured.completedAt
    expectedSep24Txn.transferReceivedAt = transferReceivedAt
    expectedSep24Txn.stellarTransactionId = STELLAR_TX_ID
    expectedSep24Txn.stellarTransactions = stellarTransactions

    JSONAssert.assertEquals(
      gson.toJson(expectedSep24Txn),
      gson.toJson(sep24TxnCapture.captured),
      JSONCompareMode.STRICT
    )

    val expectedResponse = GetTransactionResponse()
    expectedResponse.sep = SEP_24
    expectedResponse.kind = DEPOSIT
    expectedResponse.status = COMPLETED
    expectedResponse.updatedAt = sep24TxnCapture.captured.updatedAt
    expectedResponse.completedAt = sep24TxnCapture.captured.completedAt
    expectedResponse.amountExpected = Amount(null, "")
    expectedResponse.stellarTransactions = stellarTransactions

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
    assertTrue(sep24TxnCapture.captured.completedAt >= startDate)
    assertTrue(sep24TxnCapture.captured.completedAt <= endDate)
  }

  @Test
  fun test_handle_invalidStellarTransaction() {
    val transferReceivedAt = Instant.now()
    val request =
      NotifyOnchainFundsSentRequest.builder()
        .transactionId(TX_ID)
        .stellarTransactionId(STELLAR_TX_ID)
        .build()
    val txn24 = JdbcSep24Transaction()
    txn24.status = PENDING_ANCHOR.toString()
    txn24.kind = DEPOSIT.kind
    txn24.transferReceivedAt = transferReceivedAt
    val sep24TxnCapture = slot<JdbcSep24Transaction>()

    every { txn24Store.findByTransactionId(TX_ID) } returns txn24
    every { txn31Store.findByTransactionId(any()) } returns null
    every { txn24Store.save(capture(sep24TxnCapture)) } returns null
    every { horizon.getStellarTxnOperations(any()) } throws
      IOException("Invalid stellar transaction")

    val ex = assertThrows<InternalErrorException> { handler.handle(request) }
    assertEquals("Failed to retrieve Stellar transaction by ID[stellarTxId]", ex.message)

    verify(exactly = 0) { txn24Store.save(any()) }
    verify(exactly = 0) { txn31Store.save(any()) }
    verify(exactly = 0) { sepTransactionCounter.increment() }
  }

  private val paymentOperationRecord =
    """
[
  {
    "amount": "15.0000000",
    "asset_type": "native",
    "from": "testFrom",
    "to": "testTo",
    "id": 12345,
    "source_account": "testSourceAccount",
    "paging_token": "testPagingToken",
    "created_at": "2023-05-10T10:18:20Z",
    "transaction_hash": "testTxHash",
    "transaction_successful": true,
    "type": "payment",
    "links": {
      "effects": {
        "href": "https://horizon-testnet.stellar.org/operations/12345/effects",
        "templated": false
      },
      "precedes": {
        "href": "https://horizon-testnet.stellar.org/effects?order\u003dasc\u0026cursor\u003d12345",
        "templated": false
      },
      "self": {
        "href": "https://horizon-testnet.stellar.org/operations/12345",
        "templated": false
      },
      "succeeds": {
        "href": "https://horizon-testnet.stellar.org/effects?order\u003ddesc\u0026cursor\u003d12345",
        "templated": false
      },
      "transaction": {
        "href": "https://horizon-testnet.stellar.org/transactions/testTxHash",
        "templated": false
      }
    },
    "transaction": {
      "hash": "testTxHash",
      "ledger": 1234,
      "created_at": "2023-05-10T10:18:20Z",
      "source_account": "testSourceAccount",
      "fee_account": "testFeeAccount",
      "successful": true,
      "paging_token": "1234",
      "source_account_sequence": 12345,
      "max_fee": 100,
      "fee_charged": 100,
      "operation_count": 1,
      "envelope_xdr": "testEnvelopeXdr",
      "result_xdr": "testResultXdr",
      "result_meta_xdr": "resultMetaXdr",
      "signatures": [
        "testSignature1"
      ],
      "preconditions": {
        "timeBounds": {
          "minTime": 0,
          "maxTime": 1683713997
        },
        "min_account_sequence_age": 0,
        "min_account_sequence_ledger_gap": 0
      },
      "links": {
        "account": {
          "href": "https://horizon-testnet.stellar.org/accounts/testAccount",
          "templated": false
        },
        "effects": {
          "href": "https://horizon-testnet.stellar.org/transactions/testTxHash/effects{?cursor,limit,order}",
          "templated": true
        },
        "ledger": {
          "href": "https://horizon-testnet.stellar.org/ledgers/1234",
          "templated": false
        },
        "operations": {
          "href": "https://horizon-testnet.stellar.org/transactions/testTxHash/operations{?cursor,limit,order}",
          "templated": true
        },
        "precedes": {
          "href": "https://horizon-testnet.stellar.org/transactions?order\u003dasc\u0026cursor\u003d12345",
          "templated": false
        },
        "self": {
          "href": "https://horizon-testnet.stellar.org/transactions/testTxHash",
          "templated": false
        },
        "succeeds": {
          "href": "https://horizon-testnet.stellar.org/transactions?order\u003ddesc\u0026cursor\u003d12345",
          "templated": false
        }
      },
      "rate_limit_limit": 0,
      "rate_limit_remaining": 0,
      "rate_limit_reset": 0
    },
    "rate_limit_limit": 0,
    "rate_limit_remaining": 0,
    "rate_limit_reset": 0
  }
]  
"""

  private val stellarTransactions =
    """
[
  {
    "id": "stellarTxId",
    "created_at": "2023-05-10T10:18:20Z",
    "envelope": "testEnvelopeXdr",
    "payments": [
      {
        "asset_type": "native",
        "id": "12345",
        "amount": {
          "amount": "15.0000000",
          "asset": "native"
        },
        "payment_type": "payment",
        "source_account": "testFrom",
        "destination_account": "testTo"
      }
    ]
  }
]  
"""
}
