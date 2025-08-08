package org.stellar.anchor.platform.rpc

import io.mockk.*
import io.mockk.impl.annotations.MockK
import java.time.Instant
import kotlin.test.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.skyscreamer.jsonassert.JSONAssert
import org.skyscreamer.jsonassert.JSONCompareMode
import org.stellar.anchor.api.event.AnchorEvent
import org.stellar.anchor.api.exception.BadRequestException
import org.stellar.anchor.api.exception.NotFoundException
import org.stellar.anchor.api.platform.GetTransactionResponse
import org.stellar.anchor.api.platform.PlatformTransactionData
import org.stellar.anchor.api.rpc.method.GetTransactionRpcRequest
import org.stellar.anchor.api.sep.SepTransactionStatus
import org.stellar.anchor.api.shared.Amount
import org.stellar.anchor.api.shared.Customers
import org.stellar.anchor.api.shared.StellarId
import org.stellar.anchor.asset.AssetService
import org.stellar.anchor.event.EventService
import org.stellar.anchor.platform.data.JdbcSep31Transaction
import org.stellar.anchor.platform.service.TransactionService
import org.stellar.anchor.sep24.Sep24DepositInfoGenerator
import org.stellar.anchor.sep24.Sep24TransactionStore
import org.stellar.anchor.sep31.Sep31TransactionStore
import org.stellar.anchor.sep38.Sep38QuoteStore
import org.stellar.anchor.sep6.Sep6DepositInfoGenerator
import org.stellar.anchor.sep6.Sep6TransactionStore
import org.stellar.anchor.util.GsonUtils

class GetTransactionHandlerTest {
  companion object {
    private val gson = GsonUtils.getInstance()
    private const val TX_ID = "testId"
    private const val INVALID_ID = "invalidId"
    private const val EMPTY_ID_ERROR_MESSAGE = "transaction id cannot be empty"
    private const val TRANSACTION_NOT_FOUND = "transaction (id=invalidId) is not found"
  }

  @MockK(relaxed = true) private lateinit var sep38QuoteStore: Sep38QuoteStore
  @MockK(relaxed = true) private lateinit var txn6Store: Sep6TransactionStore
  @MockK(relaxed = true) private lateinit var txn24Store: Sep24TransactionStore
  @MockK(relaxed = true) private lateinit var txn31Store: Sep31TransactionStore
  @MockK(relaxed = true) private lateinit var assetService: AssetService
  @MockK(relaxed = true) private lateinit var eventService: EventService
  @MockK(relaxed = true) private lateinit var eventSession: EventService.Session
  @MockK(relaxed = true) private lateinit var sep6DepositInfoGenerator: Sep6DepositInfoGenerator
  @MockK(relaxed = true) private lateinit var sep24DepositInfoGenerator: Sep24DepositInfoGenerator
  private lateinit var transactionService: TransactionService
  private lateinit var handler: GetTransactionHandler

  @BeforeEach
  fun setup() {
    MockKAnnotations.init(this, relaxUnitFun = true)
    every { eventService.createSession(any(), EventService.EventQueue.TRANSACTION) } returns
      eventSession
    transactionService =
      TransactionService(
        txn6Store,
        txn24Store,
        txn31Store,
        sep38QuoteStore,
        assetService,
        eventService,
        sep6DepositInfoGenerator,
        sep24DepositInfoGenerator
      )
    handler = GetTransactionHandler(transactionService)
  }

  @Test
  fun test_get_Sep31Txn_byId() {
    val request = GetTransactionRpcRequest.builder().transactionId(TX_ID).build()
    val txn31 = JdbcSep31Transaction()
    txn31.status = SepTransactionStatus.PENDING_SENDER.toString()
    txn31.updatedAt = Instant.now()
    val anchorEventCapture = slot<AnchorEvent>()

    every { txn6Store.findByTransactionId(any()) } returns null
    every { txn24Store.findByTransactionId(any()) } returns null
    every { txn31Store.findByTransactionId(TX_ID) } returns txn31
    every { eventSession.publish(capture(anchorEventCapture)) } just Runs

    val response = handler.handle(request)
    verify(exactly = 0) { txn6Store.findByTransactionId(any()) }
    verify(exactly = 0) { txn24Store.findByTransactionId(any()) }
    verify(exactly = 1) { txn31Store.findByTransactionId(any()) }
    verify(exactly = 0) { eventSession.publish(any()) }

    val expectedResponse = GetTransactionResponse()
    expectedResponse.sep = PlatformTransactionData.Sep.SEP_31
    expectedResponse.kind = PlatformTransactionData.Kind.RECEIVE
    expectedResponse.status = SepTransactionStatus.PENDING_SENDER
    expectedResponse.amountIn = Amount()
    expectedResponse.amountOut = Amount()
    expectedResponse.amountExpected = Amount()
    expectedResponse.updatedAt = txn31.updatedAt
    expectedResponse.customers = Customers(StellarId(), StellarId())
    JSONAssert.assertEquals(
      gson.toJson(expectedResponse),
      gson.toJson(response),
      JSONCompareMode.STRICT
    )
  }

  @Test
  fun test_handle_emptyId() {
    val request = GetTransactionRpcRequest.builder().transactionId("").build()
    val ex = assertThrows<BadRequestException> { handler.handle(request) }
    assertEquals(EMPTY_ID_ERROR_MESSAGE, ex.message?.trimIndent())
  }

  @Test
  fun test_handle_invalidId() {
    val request = GetTransactionRpcRequest.builder().transactionId(INVALID_ID).build()
    every { txn6Store.findByTransactionId(any()) } returns null
    every { txn24Store.findByTransactionId(any()) } returns null
    every { txn31Store.findByTransactionId(INVALID_ID) } returns null
    val ex = assertThrows<NotFoundException> { handler.handle(request) }
    assertEquals(TRANSACTION_NOT_FOUND, ex.message?.trimIndent())
  }
}
