package org.stellar.anchor.platform.rpc

import io.mockk.*
import io.mockk.impl.annotations.MockK
import java.time.Instant
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.skyscreamer.jsonassert.JSONAssert
import org.skyscreamer.jsonassert.JSONCompareMode
import org.stellar.anchor.api.event.AnchorEvent
import org.stellar.anchor.api.platform.GetTransactionsResponse
import org.stellar.anchor.api.platform.TransactionsSeps
import org.stellar.anchor.api.rpc.method.GetTransactionsRpcRequest
import org.stellar.anchor.api.sep.SepTransactionStatus
import org.stellar.anchor.asset.AssetService
import org.stellar.anchor.event.EventService
import org.stellar.anchor.platform.data.JdbcSep31Transaction
import org.stellar.anchor.platform.service.TransactionService
import org.stellar.anchor.platform.utils.PlatformTransactionHelper
import org.stellar.anchor.sep24.Sep24DepositInfoGenerator
import org.stellar.anchor.sep24.Sep24TransactionStore
import org.stellar.anchor.sep31.Sep31TransactionStore
import org.stellar.anchor.sep38.Sep38QuoteStore
import org.stellar.anchor.sep6.Sep6DepositInfoGenerator
import org.stellar.anchor.sep6.Sep6TransactionStore
import org.stellar.anchor.util.GsonUtils

class GetTransactionsHandlerTest {
  companion object {
    private val gson = GsonUtils.getInstance()
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

  private lateinit var handler: GetTransactionsHandler

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
    this.handler = GetTransactionsHandler(transactionService)
  }

  @Test
  fun test_get_Sep31Txns() {
    val request = GetTransactionsRpcRequest.builder().sep(TransactionsSeps.SEP_31).build()

    val txn1 = JdbcSep31Transaction()
    txn1.status = SepTransactionStatus.PENDING_SENDER.toString()
    txn1.updatedAt = Instant.now()
    val anchorEventCapture1 = slot<AnchorEvent>()

    val txn2 = JdbcSep31Transaction()
    txn2.status = SepTransactionStatus.COMPLETED.toString()
    txn2.updatedAt = Instant.now()
    val anchorEventCapture2 = slot<AnchorEvent>()

    val allTxns = listOf(txn1, txn2)

    every { txn6Store.findTransactions(any()) } returns null
    every { txn24Store.findTransactions(any()) } returns null
    every { txn31Store.findTransactions(any()) } returns allTxns
    every { eventSession.publish(capture(anchorEventCapture1)) } just Runs
    every { eventSession.publish(capture(anchorEventCapture2)) } just Runs

    val response = handler.handle(request)
    verify(exactly = 0) { txn6Store.findByTransactionId(any()) }
    verify(exactly = 0) { txn24Store.findByTransactionId(any()) }
    verify(exactly = 1) { txn31Store.findTransactions(any()) }
    verify(exactly = 0) { eventSession.publish(any()) }

    val expectedResponse = GetTransactionsResponse()
    expectedResponse.records =
      allTxns
        .map { txn -> PlatformTransactionHelper.toGetTransactionResponse(txn, assetService) }
        .toList()

    JSONAssert.assertEquals(
      gson.toJson(expectedResponse),
      gson.toJson(response),
      JSONCompareMode.STRICT
    )
  }
}
