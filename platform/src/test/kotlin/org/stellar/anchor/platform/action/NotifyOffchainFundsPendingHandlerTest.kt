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
import org.stellar.anchor.api.exception.rpc.InvalidParamsException
import org.stellar.anchor.api.exception.rpc.InvalidRequestException
import org.stellar.anchor.api.platform.GetTransactionResponse
import org.stellar.anchor.api.platform.PlatformTransactionData.Kind.DEPOSIT
import org.stellar.anchor.api.platform.PlatformTransactionData.Kind.WITHDRAWAL
import org.stellar.anchor.api.platform.PlatformTransactionData.Sep.SEP_24
import org.stellar.anchor.api.rpc.action.NotifyOffchainFundsPendingRequest
import org.stellar.anchor.api.sep.SepTransactionStatus.*
import org.stellar.anchor.api.shared.Amount
import org.stellar.anchor.asset.AssetService
import org.stellar.anchor.platform.data.JdbcSep24Transaction
import org.stellar.anchor.platform.validator.RequestValidator
import org.stellar.anchor.sep24.Sep24TransactionStore
import org.stellar.anchor.sep31.Sep31TransactionStore
import org.stellar.anchor.util.GsonUtils

class NotifyOffchainFundsPendingHandlerTest {

  companion object {
    private val gson = GsonUtils.getInstance()
    private const val TX_ID = "testId"
  }

  @MockK(relaxed = true) private lateinit var txn24Store: Sep24TransactionStore

  @MockK(relaxed = true) private lateinit var txn31Store: Sep31TransactionStore

  @MockK(relaxed = true) private lateinit var requestValidator: RequestValidator

  @MockK(relaxed = true) private lateinit var assetService: AssetService

  private lateinit var handler: NotifyOffchainFundsPendingHandler

  @BeforeEach
  fun setup() {
    MockKAnnotations.init(this, relaxUnitFun = true)
    this.handler =
      NotifyOffchainFundsPendingHandler(txn24Store, txn31Store, requestValidator, assetService)
  }

  @Test
  fun test_handle_unsupportedProtocol() {
    val request = NotifyOffchainFundsPendingRequest.builder().transactionId(TX_ID).build()
    val txn24 = JdbcSep24Transaction()
    txn24.status = PENDING_ANCHOR.toString()
    txn24.kind = WITHDRAWAL.kind
    txn24.transferReceivedAt = Instant.now()
    val spyTxn24 = spyk(txn24)

    every { txn24Store.findByTransactionId(TX_ID) } returns spyTxn24
    every { txn31Store.findByTransactionId(any()) } returns null
    every { spyTxn24.protocol } returns "38"

    val ex = assertThrows<InvalidRequestException> { handler.handle(request) }
    assertEquals(
      "Action[notify_offchain_funds_pending] is not supported for status[pending_anchor], kind[null] and protocol[38]",
      ex.message
    )
  }

  @Test
  fun test_handle_unsupportedStatus() {
    val request = NotifyOffchainFundsPendingRequest.builder().transactionId(TX_ID).build()
    val txn24 = JdbcSep24Transaction()
    txn24.status = PENDING_TRUST.toString()
    txn24.kind = WITHDRAWAL.kind
    txn24.transferReceivedAt = Instant.now()

    every { txn24Store.findByTransactionId(TX_ID) } returns txn24
    every { txn31Store.findByTransactionId(any()) } returns null

    val ex = assertThrows<InvalidRequestException> { handler.handle(request) }
    assertEquals(
      "Action[notify_offchain_funds_pending] is not supported for status[pending_trust], kind[withdrawal] and protocol[24]",
      ex.message
    )
  }

  @Test
  fun test_handle_unsupportedKind() {
    val request = NotifyOffchainFundsPendingRequest.builder().transactionId(TX_ID).build()
    val txn24 = JdbcSep24Transaction()
    txn24.status = PENDING_ANCHOR.toString()
    txn24.kind = DEPOSIT.kind
    txn24.transferReceivedAt = Instant.now()

    every { txn24Store.findByTransactionId(TX_ID) } returns txn24
    every { txn31Store.findByTransactionId(any()) } returns null

    val ex = assertThrows<InvalidRequestException> { handler.handle(request) }
    assertEquals(
      "Action[notify_offchain_funds_pending] is not supported for status[pending_anchor], kind[deposit] and protocol[24]",
      ex.message
    )
  }

  @Test
  fun test_handle_transferNotReceived() {
    val request = NotifyOffchainFundsPendingRequest.builder().transactionId(TX_ID).build()
    val txn24 = JdbcSep24Transaction()
    txn24.status = PENDING_ANCHOR.toString()
    txn24.kind = WITHDRAWAL.kind

    every { txn24Store.findByTransactionId(TX_ID) } returns txn24
    every { txn31Store.findByTransactionId(any()) } returns null

    val ex = assertThrows<InvalidRequestException> { handler.handle(request) }
    assertEquals(
      "Action[notify_offchain_funds_pending] is not supported for status[pending_anchor], kind[withdrawal] and protocol[24]",
      ex.message
    )
  }

  @Test
  fun test_handle_invalidRequest() {
    val request = NotifyOffchainFundsPendingRequest.builder().transactionId(TX_ID).build()
    val txn24 = JdbcSep24Transaction()
    txn24.status = PENDING_ANCHOR.toString()
    txn24.kind = WITHDRAWAL.kind
    txn24.transferReceivedAt = Instant.now()

    every { txn24Store.findByTransactionId(TX_ID) } returns txn24
    every { txn31Store.findByTransactionId(any()) } returns null
    every { requestValidator.validate(request) } throws InvalidParamsException("Invalid request")

    val ex = assertThrows<InvalidParamsException> { handler.handle(request) }
    assertEquals("Invalid request", ex.message?.trimIndent())
  }

  @Test
  fun test_handle_ok_deposit_withExternalTxId() {
    val transferReceivedAt = Instant.now()
    val request =
      NotifyOffchainFundsPendingRequest.builder()
        .transactionId(TX_ID)
        .externalTransactionId("externalTxId")
        .build()
    val txn24 = JdbcSep24Transaction()
    txn24.status = PENDING_ANCHOR.toString()
    txn24.kind = WITHDRAWAL.kind
    txn24.transferReceivedAt = transferReceivedAt
    val sep24TxnCapture = slot<JdbcSep24Transaction>()

    every { txn24Store.findByTransactionId(TX_ID) } returns txn24
    every { txn31Store.findByTransactionId(any()) } returns null
    every { txn24Store.save(capture(sep24TxnCapture)) } returns null

    val startDate = Instant.now()
    val response = handler.handle(request)
    val endDate = Instant.now()

    verify(exactly = 0) { txn31Store.save(any()) }

    val expectedSep24Txn = JdbcSep24Transaction()
    expectedSep24Txn.kind = WITHDRAWAL.kind
    expectedSep24Txn.status = PENDING_EXTERNAL.toString()
    expectedSep24Txn.updatedAt = sep24TxnCapture.captured.updatedAt
    expectedSep24Txn.transferReceivedAt = transferReceivedAt
    expectedSep24Txn.externalTransactionId = "externalTxId"

    JSONAssert.assertEquals(
      gson.toJson(expectedSep24Txn),
      gson.toJson(sep24TxnCapture.captured),
      JSONCompareMode.STRICT
    )

    val expectedResponse = GetTransactionResponse()
    expectedResponse.sep = SEP_24
    expectedResponse.kind = WITHDRAWAL
    expectedResponse.status = PENDING_EXTERNAL
    expectedResponse.externalTransactionId = "externalTxId"
    expectedResponse.updatedAt = sep24TxnCapture.captured.updatedAt
    expectedResponse.transferReceivedAt = transferReceivedAt
    expectedResponse.amountExpected = Amount(null, "")

    JSONAssert.assertEquals(
      gson.toJson(expectedResponse),
      gson.toJson(response),
      JSONCompareMode.STRICT
    )

    assertTrue(expectedSep24Txn.updatedAt >= startDate)
    assertTrue(expectedSep24Txn.updatedAt <= endDate)
  }

  @Test
  fun test_handle_ok_deposit_withoutExternalTxId() {
    val transferReceivedAt = Instant.now()
    val request = NotifyOffchainFundsPendingRequest.builder().transactionId(TX_ID).build()
    val txn24 = JdbcSep24Transaction()
    txn24.status = PENDING_ANCHOR.toString()
    txn24.kind = WITHDRAWAL.kind
    txn24.transferReceivedAt = transferReceivedAt
    val sep24TxnCapture = slot<JdbcSep24Transaction>()

    every { txn24Store.findByTransactionId(TX_ID) } returns txn24
    every { txn31Store.findByTransactionId(any()) } returns null
    every { txn24Store.save(capture(sep24TxnCapture)) } returns null

    val startDate = Instant.now()
    val response = handler.handle(request)
    val endDate = Instant.now()

    verify(exactly = 0) { txn31Store.save(any()) }

    val expectedSep24Txn = JdbcSep24Transaction()
    expectedSep24Txn.kind = WITHDRAWAL.kind
    expectedSep24Txn.status = PENDING_EXTERNAL.toString()
    expectedSep24Txn.updatedAt = sep24TxnCapture.captured.updatedAt
    expectedSep24Txn.transferReceivedAt = transferReceivedAt

    JSONAssert.assertEquals(
      gson.toJson(expectedSep24Txn),
      gson.toJson(sep24TxnCapture.captured),
      JSONCompareMode.STRICT
    )

    val expectedResponse = GetTransactionResponse()
    expectedResponse.sep = SEP_24
    expectedResponse.kind = WITHDRAWAL
    expectedResponse.status = PENDING_EXTERNAL
    expectedResponse.updatedAt = sep24TxnCapture.captured.updatedAt
    expectedResponse.transferReceivedAt = transferReceivedAt
    expectedResponse.amountExpected = Amount(null, "")

    JSONAssert.assertEquals(
      gson.toJson(expectedResponse),
      gson.toJson(response),
      JSONCompareMode.STRICT
    )

    assertTrue(expectedSep24Txn.updatedAt >= startDate)
    assertTrue(expectedSep24Txn.updatedAt <= endDate)
  }
}
