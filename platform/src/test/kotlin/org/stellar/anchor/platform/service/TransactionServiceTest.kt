package org.stellar.anchor.platform.service

import io.mockk.MockKAnnotations
import io.mockk.clearAllMocks
import io.mockk.every
import io.mockk.impl.annotations.MockK
import io.mockk.unmockkAll
import java.time.Instant
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.stellar.anchor.api.exception.AnchorException
import org.stellar.anchor.api.exception.BadRequestException
import org.stellar.anchor.api.exception.NotFoundException
import org.stellar.anchor.api.platform.GetTransactionResponse
import org.stellar.anchor.api.sep.AssetInfo
import org.stellar.anchor.api.shared.*
import org.stellar.anchor.api.shared.RefundPayment
import org.stellar.anchor.asset.AssetService
import org.stellar.anchor.event.models.TransactionEvent
import org.stellar.anchor.platform.data.JdbcSep31RefundPayment.JdbcRefundPayment
import org.stellar.anchor.platform.data.JdbcSep31Refunds
import org.stellar.anchor.platform.data.JdbcSep31Transaction
import org.stellar.anchor.sep31.*
import org.stellar.anchor.sep38.Sep38QuoteStore

class TransactionServiceTest {
  companion object {
    private const val fiatUSD = "iso4217:USD"
    private const val stellarUSDC =
      "stellar:USDC:GBBD47IF6LWK7P7MDEVSCWR7DPUWV3NY3DTQEVFL4NAT4AQH3ZLLFLA5"
    private const val TEST_ACCOUNT = "GCHLHDBOKG2JWMJQBTLSL5XG6NO7ESXI2TAQKZXCXWXB5WI2X6W233PR"
    private const val TEST_MEMO = "test memo"
  }

  @MockK(relaxed = true) private lateinit var sep38QuoteStore: Sep38QuoteStore
  @MockK(relaxed = true) private lateinit var sep31TransactionStore: Sep31TransactionStore
  @MockK(relaxed = true) private lateinit var assetService: AssetService
  private lateinit var transactionService: TransactionService

  @BeforeEach
  fun setup() {
    MockKAnnotations.init(this, relaxUnitFun = true)
    transactionService = TransactionService(sep38QuoteStore, sep31TransactionStore, assetService)
  }

  @AfterEach
  fun tearDown() {
    clearAllMocks()
    unmockkAll()
  }

  @Test
  fun test_getTransaction_failure() {
    // null tx id is rejected with 400
    var ex: AnchorException = assertThrows { transactionService.getTransaction(null) }
    assertInstanceOf(BadRequestException::class.java, ex)
    assertEquals("transaction id cannot be empty", ex.message)

    // empty tx id is rejected with 400
    ex = assertThrows { transactionService.getTransaction("") }
    assertInstanceOf(BadRequestException::class.java, ex)
    assertEquals("transaction id cannot be empty", ex.message)

    // non-existent transaction is rejected with 404
    every { sep31TransactionStore.findByTransactionId(any()) } returns null
    ex = assertThrows { transactionService.getTransaction("not-found-tx-id") }
    assertInstanceOf(NotFoundException::class.java, ex)
    assertEquals("transaction (id=not-found-tx-id) is not found", ex.message)
  }

  @Test
  fun test_getTransaction() {
    val txId = "a4baff5f-778c-43d6-bbef-3e9fb41d096e"

    // Mock the store
    every { sep31TransactionStore.newTransaction() } returns JdbcSep31Transaction()
    every { sep31TransactionStore.newRefunds() } returns JdbcSep31Refunds()
    every { sep31TransactionStore.newRefundPayment() } answers { JdbcRefundPayment() }

    // mock time
    val mockStartedAt = Instant.now().minusSeconds(180)
    val mockUpdatedAt = mockStartedAt.plusSeconds(60)
    val mockTransferReceivedAt = mockUpdatedAt.plusSeconds(60)
    val mockCompletedAt = mockTransferReceivedAt.plusSeconds(60)

    // mock refunds
    val mockRefundPayment1 =
      RefundPaymentBuilder(sep31TransactionStore).id("1111").amount("50.0000").fee("4.0000").build()
    val mockRefundPayment2 =
      RefundPaymentBuilder(sep31TransactionStore).id("2222").amount("40.0000").fee("4.0000").build()
    val mockRefunds =
      RefundsBuilder(sep31TransactionStore)
        .amountRefunded("90.0000")
        .amountFee("8.0000")
        .payments(listOf(mockRefundPayment1, mockRefundPayment2))
        .build()

    // mock missing SEP-31 "transaction.fields"
    val mockMissingFields = AssetInfo.Sep31TxnFieldSpecs()
    mockMissingFields.transaction =
      mapOf(
        "receiver_account_number" to
          AssetInfo.Sep31TxnFieldSpec("bank account number of the destination", null, false),
      )

    // mock the database SEP-31 transaction
    val mockSep31Transaction =
      Sep31TransactionBuilder(sep31TransactionStore)
        .id(txId)
        .status(TransactionEvent.Status.PENDING_RECEIVER.status)
        .statusEta(120)
        .amountExpected("100")
        .amountIn("100.0000")
        .amountInAsset(fiatUSD)
        .amountOut("98.0000000")
        .amountOutAsset(stellarUSDC)
        .amountFee("2.0000")
        .amountFeeAsset(fiatUSD)
        .stellarAccountId(TEST_ACCOUNT)
        .stellarMemo(TEST_MEMO)
        .stellarMemoType("text")
        .startedAt(mockStartedAt)
        .updatedAt(mockUpdatedAt)
        .transferReceivedAt(mockTransferReceivedAt)
        .completedAt(mockCompletedAt)
        .stellarTransactionId("2b862ac297c93e2db43fc58d407cc477396212bce5e6d5f61789f963d5a11300")
        .externalTransactionId("external-tx-id")
        .refunded(true)
        .refunds(mockRefunds)
        .requiredInfoMessage("Please don't forget to foo bar")
        .requiredInfoUpdates(mockMissingFields)
        .quoteId("quote-id")
        .clientDomain("test.com")
        .senderId("6c1770b0-0ea4-11ed-861d-0242ac120002")
        .receiverId("31212353-f265-4dba-9eb4-0bbeda3ba7f2")
        .creator(StellarId("141ee445-f32c-4c38-9d25-f4475d6c5558", null))
        .build()
    every { sep31TransactionStore.findByTransactionId(txId) } returns mockSep31Transaction

    val gotGetTransactionResponse = transactionService.getTransaction(txId)

    val wantRefunds: Refund =
      Refund.builder()
        .amountRefunded(Amount("90.0000", fiatUSD))
        .amountFee(Amount("8.0000", fiatUSD))
        .payments(
          arrayOf(
            RefundPayment.builder()
              .id("1111")
              .idType(RefundPayment.IdType.STELLAR)
              .amount(Amount("50.0000", fiatUSD))
              .fee(Amount("4.0000", fiatUSD))
              .requestedAt(null)
              .refundedAt(null)
              .build(),
            RefundPayment.builder()
              .id("2222")
              .idType(RefundPayment.IdType.STELLAR)
              .amount(Amount("40.0000", fiatUSD))
              .fee(Amount("4.0000", fiatUSD))
              .requestedAt(null)
              .refundedAt(null)
              .build()
          )
        )
        .build()

    val wantStellarTransaction = GetTransactionResponse.StellarTransaction()
    wantStellarTransaction.id = "2b862ac297c93e2db43fc58d407cc477396212bce5e6d5f61789f963d5a11300"

    val wantGetTransactionResponse: GetTransactionResponse =
      GetTransactionResponse.builder()
        .id(txId)
        .sep(31)
        .kind("receive")
        .status(TransactionEvent.Status.PENDING_RECEIVER.status)
        .amountExpected(Amount("100", fiatUSD))
        .amountIn(Amount("100.0000", fiatUSD))
        .amountOut(Amount("98.0000000", stellarUSDC))
        .amountFee(Amount("2.0000", fiatUSD))
        .quoteId("quote-id")
        .startedAt(mockStartedAt)
        .updatedAt(mockUpdatedAt)
        .completedAt(mockCompletedAt)
        .transferReceivedAt(mockTransferReceivedAt)
        .message("Please don't forget to foo bar")
        .refunds(wantRefunds)
        .stellarTransactions(listOf(wantStellarTransaction))
        .externalTransactionId("external-tx-id")
        .customers(
          Customers(
            StellarId("6c1770b0-0ea4-11ed-861d-0242ac120002", null),
            StellarId("31212353-f265-4dba-9eb4-0bbeda3ba7f2", null)
          )
        )
        .creator(StellarId("141ee445-f32c-4c38-9d25-f4475d6c5558", null))
        .build()
    assertEquals(wantGetTransactionResponse, gotGetTransactionResponse)
  }
}
