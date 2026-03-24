package org.stellar.anchor.platform.job

import io.mockk.MockKAnnotations
import io.mockk.Runs
import io.mockk.every
import io.mockk.impl.annotations.MockK
import io.mockk.just
import io.mockk.slot
import io.mockk.verify
import java.time.Instant
import java.util.*
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.stellar.anchor.api.custody.fireblocks.TransactionDetails
import org.stellar.anchor.api.custody.fireblocks.TransactionStatus
import org.stellar.anchor.api.exception.FireblocksException
import org.stellar.anchor.api.platform.PlatformTransactionData
import org.stellar.anchor.platform.config.FireblocksConfig
import org.stellar.anchor.platform.custody.CustodyPayment
import org.stellar.anchor.platform.custody.CustodyPaymentService
import org.stellar.anchor.platform.custody.CustodyTransactionService
import org.stellar.anchor.platform.custody.fireblocks.FireblocksEventService
import org.stellar.anchor.platform.data.CustodyTransactionStatus
import org.stellar.anchor.platform.data.JdbcCustodyTransaction
import org.stellar.anchor.platform.data.JdbcCustodyTransactionRepo
import org.stellar.anchor.platform.fireblocks.job.FireblocksTransactionsReconciliationJob

class FireblocksTransactionsReconciliationJobTest {

  companion object {
    private const val EXTERNAL_TXN_ID = "TRANSACTION_ID"
    private const val MEMO = "1234567890"
    private const val DESTINATION_ADDRESS =
      "GBX3C62RHF6C7EVG4QXJ4PORIRSQLPBBOIDSKYRLK4H2JBTPTJZM4V6E"
    private val START_TIME = Instant.now().minusSeconds(5)
  }

  @MockK(relaxed = true) private lateinit var fireblocksConfig: FireblocksConfig
  @MockK(relaxed = true)
  private lateinit var custodyPaymentService: CustodyPaymentService<TransactionDetails>
  @MockK(relaxed = true) private lateinit var fireblocksEventService: FireblocksEventService
  @MockK(relaxed = true) private lateinit var custodyTransactionRepo: JdbcCustodyTransactionRepo
  private lateinit var custodyTransactionService: CustodyTransactionService

  private lateinit var reconciliationJob: FireblocksTransactionsReconciliationJob

  @BeforeEach
  fun setup() {
    MockKAnnotations.init(this, relaxUnitFun = true)
    custodyTransactionService =
      CustodyTransactionService(custodyTransactionRepo, custodyPaymentService)

    reconciliationJob =
      FireblocksTransactionsReconciliationJob(
        fireblocksConfig,
        custodyPaymentService,
        fireblocksEventService,
        custodyTransactionService
      )
  }

  @ParameterizedTest
  @EnumSource(
    value = TransactionStatus::class,
    mode = EnumSource.Mode.INCLUDE,
    names = ["CONFIRMING", "COMPLETED", "CANCELLED", "BLOCKED", "FAILED"]
  )
  fun `reconcile outbound transactions - successful reconciliation`(status: TransactionStatus) {
    val custodyTxn =
      JdbcCustodyTransaction.builder()
        .externalTxId(EXTERNAL_TXN_ID)
        .status(CustodyTransactionStatus.SUBMITTED.toString())
        .build()
    val fireblocksTxn = TransactionDetails.builder().status(status).build()
    val custodyPayment = CustodyPayment.builder().build()

    every {
      custodyTransactionRepo.findAllByStatusAndExternalTxIdNotNull(
        CustodyTransactionStatus.SUBMITTED.toString()
      )
    } returns listOf(custodyTxn)
    every { custodyPaymentService.getTransactionById(EXTERNAL_TXN_ID) } returns fireblocksTxn
    every { fireblocksEventService.convert(fireblocksTxn) } returns Optional.of(custodyPayment)

    reconciliationJob.reconcileTransactions()

    verify(exactly = 1) { custodyPaymentService.getTransactionById(EXTERNAL_TXN_ID) }
    verify(exactly = 1) { fireblocksEventService.handlePayment(custodyPayment) }
  }

  @Test
  fun `reconcile outbound transactions - no transactions to reconcile`() {
    every {
      custodyTransactionRepo.findAllByStatusAndExternalTxIdNotNull(
        CustodyTransactionStatus.SUBMITTED.toString()
      )
    } returns emptyList()

    reconciliationJob.reconcileTransactions()

    verify(exactly = 0) { custodyPaymentService.getTransactionById(any()) }
  }

  @ParameterizedTest
  @EnumSource(
    value = TransactionStatus::class,
    mode = EnumSource.Mode.INCLUDE,
    names =
      [
        "QUEUED",
        "PENDING_AUTHORIZATION",
        "PENDING_SIGNATURE",
        "BROADCASTING",
        "PENDING_3RD_PARTY_MANUAL_APPROVAL",
        "PENDING_3RD_PARTY",
        "PARTIALLY_COMPLETED",
        "PENDING_AML_SCREENING",
        "REJECTED"
      ]
  )
  fun `reconcile outbound transactions - attempt increase`(status: TransactionStatus) {
    val attemptCount = 0
    val custodyTxn =
      JdbcCustodyTransaction.builder()
        .externalTxId(EXTERNAL_TXN_ID)
        .status(CustodyTransactionStatus.SUBMITTED.toString())
        .reconciliationAttemptCount(attemptCount)
        .build()
    val fireblocksTxn = TransactionDetails.builder().status(status).build()

    val requestCapture = slot<JdbcCustodyTransaction>()
    every { custodyTransactionService.updateCustodyTransaction(capture(requestCapture)) } just Runs
    every { custodyTransactionService.outboundTransactionsEligibleForReconciliation } returns
      listOf(custodyTxn)
    every { custodyPaymentService.getTransactionById(EXTERNAL_TXN_ID) } returns fireblocksTxn
    every { custodyTransactionRepo.save(any()) } returns null
    every { fireblocksConfig.reconciliation.maxAttempts } returns 10

    reconciliationJob.reconcileTransactions()

    verify(exactly = 1) { custodyPaymentService.getTransactionById(EXTERNAL_TXN_ID) }
    verify(exactly = 1) { custodyTransactionService.updateCustodyTransaction(any()) }

    assertEquals(CustodyTransactionStatus.SUBMITTED.toString(), custodyTxn.status)
    assertEquals(1, custodyTxn.reconciliationAttemptCount)
  }

  @Test
  fun `reconcile outbound transactions - change_status_to_failed`() {
    val attemptCount = 9
    val custodyTxn =
      JdbcCustodyTransaction.builder()
        .externalTxId(EXTERNAL_TXN_ID)
        .status(CustodyTransactionStatus.SUBMITTED.toString())
        .reconciliationAttemptCount(attemptCount)
        .build()
    val fireblocksTxn = TransactionDetails.builder().status(TransactionStatus.BROADCASTING).build()

    val requestCapture = slot<JdbcCustodyTransaction>()
    every { custodyTransactionService.updateCustodyTransaction(capture(requestCapture)) } just Runs
    every { custodyTransactionService.outboundTransactionsEligibleForReconciliation } returns
      listOf(custodyTxn)
    every { custodyPaymentService.getTransactionById(EXTERNAL_TXN_ID) } returns fireblocksTxn
    every { fireblocksConfig.reconciliation.maxAttempts } returns 10
    every { custodyTransactionRepo.save(any()) } returns null

    reconciliationJob.reconcileTransactions()

    verify(exactly = 1) { custodyPaymentService.getTransactionById(EXTERNAL_TXN_ID) }
    verify(exactly = 1) { custodyTransactionService.updateCustodyTransaction(any()) }

    assertEquals(CustodyTransactionStatus.FAILED.toString(), custodyTxn.status)
    assertEquals(10, custodyTxn.reconciliationAttemptCount)
  }

  @Test
  fun `reconcile outbound transactions - handle exception`() {
    val attemptCount = 9
    val custodyTxn =
      JdbcCustodyTransaction.builder()
        .externalTxId(EXTERNAL_TXN_ID)
        .status(CustodyTransactionStatus.SUBMITTED.toString())
        .reconciliationAttemptCount(attemptCount)
        .build()

    every { custodyTransactionService.outboundTransactionsEligibleForReconciliation } returns
      listOf(custodyTxn)
    every { custodyPaymentService.getTransactionById(EXTERNAL_TXN_ID) } throws
      FireblocksException("Too many requests", 429)

    assertDoesNotThrow { reconciliationJob.reconcileTransactions() }
  }

  @ParameterizedTest
  @EnumSource(
    value = TransactionStatus::class,
    mode = EnumSource.Mode.INCLUDE,
    names = ["CONFIRMING", "COMPLETED", "CANCELLED", "BLOCKED", "FAILED"]
  )
  fun `reconcile inbound transactions - successful reconciliation`(status: TransactionStatus) {
    val custodyTxn =
      JdbcCustodyTransaction.builder()
        .status(CustodyTransactionStatus.CREATED.toString())
        .memo(MEMO)
        .toAccount(DESTINATION_ADDRESS)
        .createdAt(START_TIME)
        .build()
    val fireblocksTxn1 =
      TransactionDetails.builder()
        .id("1")
        .status(status)
        .destinationAddress(DESTINATION_ADDRESS)
        .destinationTag(MEMO)
        .build()
    val fireblocksTxn2 =
      TransactionDetails.builder()
        .id("2")
        .status(status)
        .destinationAddress(DESTINATION_ADDRESS)
        .destinationTag(MEMO)
        .build()
    val custodyPayment = CustodyPayment.builder().build()

    every {
      custodyTransactionRepo.findAllByStatusAndKindIn(
        CustodyTransactionStatus.CREATED.toString(),
        mutableSetOf(
          PlatformTransactionData.Kind.RECEIVE.getKind(),
          PlatformTransactionData.Kind.WITHDRAWAL.getKind()
        )
      )
    } returns listOf(custodyTxn)
    every { custodyPaymentService.getTransactionsByTimeRange(START_TIME, any()) } returns
      listOf(fireblocksTxn1, fireblocksTxn2)
    every { fireblocksEventService.convert(fireblocksTxn1) } returns Optional.of(custodyPayment)

    reconciliationJob.reconcileTransactions()

    verify(exactly = 1) { custodyPaymentService.getTransactionsByTimeRange(any(), any()) }
    verify(exactly = 1) { fireblocksEventService.handlePayment(custodyPayment) }
  }

  @Test
  fun `reconcile inbound transactions - no transactions to reconcile`() {
    every {
      custodyTransactionRepo.findAllByStatusAndKindIn(
        CustodyTransactionStatus.CREATED.toString(),
        mutableSetOf(
          PlatformTransactionData.Kind.RECEIVE.getKind(),
          PlatformTransactionData.Kind.WITHDRAWAL.getKind()
        )
      )
    } returns emptyList()

    reconciliationJob.reconcileTransactions()

    verify(exactly = 0) { custodyPaymentService.getTransactionsByTimeRange(any(), any()) }
  }

  @Test
  fun `reconcile inbound transactions - no Fireblocks transactions within range`() {
    val custodyTxn =
      JdbcCustodyTransaction.builder()
        .status(CustodyTransactionStatus.CREATED.toString())
        .memo(MEMO)
        .toAccount(DESTINATION_ADDRESS)
        .createdAt(START_TIME)
        .build()

    every {
      custodyTransactionRepo.findAllByStatusAndKindIn(
        CustodyTransactionStatus.CREATED.toString(),
        mutableSetOf(
          PlatformTransactionData.Kind.RECEIVE.getKind(),
          PlatformTransactionData.Kind.WITHDRAWAL.getKind()
        )
      )
    } returns listOf(custodyTxn)

    every { custodyPaymentService.getTransactionsByTimeRange(any(), any()) } returns listOf()

    reconciliationJob.reconcileTransactions()

    verify(exactly = 0) { fireblocksEventService.handlePayment(any()) }
  }

  @ParameterizedTest
  @EnumSource(
    value = TransactionStatus::class,
    mode = EnumSource.Mode.INCLUDE,
    names =
      [
        "QUEUED",
        "PENDING_AUTHORIZATION",
        "PENDING_SIGNATURE",
        "BROADCASTING",
        "PENDING_3RD_PARTY_MANUAL_APPROVAL",
        "PENDING_3RD_PARTY",
        "PARTIALLY_COMPLETED",
        "PENDING_AML_SCREENING",
        "REJECTED"
      ]
  )
  fun `reconcile inbound transactions - attempt increase`(status: TransactionStatus) {
    val attemptCount = 0
    val custodyTxn =
      JdbcCustodyTransaction.builder()
        .status(CustodyTransactionStatus.CREATED.toString())
        .memo(MEMO)
        .toAccount(DESTINATION_ADDRESS)
        .createdAt(START_TIME)
        .reconciliationAttemptCount(attemptCount)
        .build()
    val fireblocksTxn =
      TransactionDetails.builder()
        .status(status)
        .destinationAddress(DESTINATION_ADDRESS)
        .destinationTag(MEMO)
        .build()
    val custodyPayment = CustodyPayment.builder().build()

    every {
      custodyTransactionRepo.findAllByStatusAndKindIn(
        CustodyTransactionStatus.CREATED.toString(),
        mutableSetOf(
          PlatformTransactionData.Kind.RECEIVE.getKind(),
          PlatformTransactionData.Kind.WITHDRAWAL.getKind()
        )
      )
    } returns listOf(custodyTxn)
    every { custodyPaymentService.getTransactionsByTimeRange(START_TIME, any()) } returns
      listOf(fireblocksTxn)
    every { fireblocksEventService.convert(fireblocksTxn) } returns Optional.of(custodyPayment)
    every { custodyTransactionRepo.save(any()) } returns null
    every { fireblocksConfig.reconciliation.maxAttempts } returns 10

    reconciliationJob.reconcileTransactions()

    verify(exactly = 1) { custodyPaymentService.getTransactionsByTimeRange(START_TIME, any()) }
    verify(exactly = 1) { custodyTransactionService.updateCustodyTransaction(any()) }

    assertEquals(CustodyTransactionStatus.CREATED.toString(), custodyTxn.status)
    assertEquals(1, custodyTxn.reconciliationAttemptCount)
  }

  @Test
  fun `reconcile inbound transactions - change_status_to_failed`() {
    val attemptCount = 9
    val custodyTxn =
      JdbcCustodyTransaction.builder()
        .status(CustodyTransactionStatus.CREATED.toString())
        .memo(MEMO)
        .toAccount(DESTINATION_ADDRESS)
        .createdAt(START_TIME)
        .reconciliationAttemptCount(attemptCount)
        .build()
    val fireblocksTxn =
      TransactionDetails.builder()
        .status(TransactionStatus.PENDING_SIGNATURE)
        .destinationAddress(DESTINATION_ADDRESS)
        .destinationTag(MEMO)
        .build()
    val custodyPayment = CustodyPayment.builder().build()

    every {
      custodyTransactionRepo.findAllByStatusAndKindIn(
        CustodyTransactionStatus.CREATED.toString(),
        mutableSetOf(
          PlatformTransactionData.Kind.RECEIVE.getKind(),
          PlatformTransactionData.Kind.WITHDRAWAL.getKind()
        )
      )
    } returns listOf(custodyTxn)
    every { custodyPaymentService.getTransactionsByTimeRange(START_TIME, any()) } returns
      listOf(fireblocksTxn)
    every { fireblocksEventService.convert(fireblocksTxn) } returns Optional.of(custodyPayment)
    every { fireblocksConfig.reconciliation.maxAttempts } returns 10
    every { custodyTransactionRepo.save(any()) } returns null

    reconciliationJob.reconcileTransactions()

    verify(exactly = 1) { custodyPaymentService.getTransactionsByTimeRange(START_TIME, any()) }
    verify(exactly = 1) { custodyTransactionService.updateCustodyTransaction(any()) }

    assertEquals(CustodyTransactionStatus.FAILED.toString(), custodyTxn.status)
    assertEquals(10, custodyTxn.reconciliationAttemptCount)
  }

  @Test
  fun `reconcile inbound transactions - handle exception`() {
    val custodyTxn =
      JdbcCustodyTransaction.builder()
        .externalTxId(EXTERNAL_TXN_ID)
        .status(CustodyTransactionStatus.SUBMITTED.toString())
        .createdAt(START_TIME)
        .build()

    every { custodyTransactionService.inboundTransactionsEligibleForReconciliation } returns
      listOf(custodyTxn)
    every { custodyPaymentService.getTransactionsByTimeRange(START_TIME, any()) } throws
      FireblocksException("Too many requests", 429)

    assertDoesNotThrow { reconciliationJob.reconcileTransactions() }
  }
}
