package org.stellar.anchor.platform.data

import io.mockk.MockKAnnotations
import io.mockk.every
import io.mockk.impl.annotations.MockK
import io.mockk.slot
import io.mockk.verify
import java.time.Instant
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.data.domain.Pageable
import org.stellar.anchor.api.exception.SepValidationException
import org.stellar.anchor.api.sep.sep6.GetTransactionsRequest
import org.stellar.anchor.util.TransactionQueryLimits

class JdbcSep6TransactionStoreTest {

  @MockK(relaxed = true) private lateinit var txnRepo: JdbcSep6TransactionRepo

  private lateinit var store: JdbcSep6TransactionStore

  @BeforeEach
  fun setUp() {
    MockKAnnotations.init(this, relaxUnitFun = true)
    store = JdbcSep6TransactionStore(txnRepo)
  }

  @Test
  fun `findTransactions uses database-level pagination with default limit`() {
    val request = GetTransactionsRequest.builder().assetCode("USDC").build()

    every { txnRepo.findTransactionsWithFilters(any(), any(), any(), any(), any(), any()) } returns
      emptyList()

    store.findTransactions("GACCOUNT", null, request)

    val pageableSlot = slot<Pageable>()
    verify {
      txnRepo.findTransactionsWithFilters(
        eq("GACCOUNT"),
        eq("USDC"),
        isNull(),
        any(),
        any(),
        capture(pageableSlot)
      )
    }
    assertEquals(TransactionQueryLimits.DEFAULT_LIMIT, pageableSlot.captured.pageSize)
    assertEquals(0, pageableSlot.captured.pageNumber)
  }

  @Test
  fun `findTransactions with zero or negative limit uses default`() {
    val request = GetTransactionsRequest.builder().assetCode("USDC").limit(0).build()

    every { txnRepo.findTransactionsWithFilters(any(), any(), any(), any(), any(), any()) } returns
      emptyList()

    store.findTransactions("GACCOUNT", null, request)

    val pageableSlot = slot<Pageable>()
    verify {
      txnRepo.findTransactionsWithFilters(any(), any(), any(), any(), any(), capture(pageableSlot))
    }
    assertEquals(TransactionQueryLimits.DEFAULT_LIMIT, pageableSlot.captured.pageSize)
  }

  @Test
  fun `findTransactions caps limit at MAX_LIMIT`() {
    val request = GetTransactionsRequest.builder().assetCode("USDC").limit(50000).build()

    every { txnRepo.findTransactionsWithFilters(any(), any(), any(), any(), any(), any()) } returns
      emptyList()

    store.findTransactions("GACCOUNT", null, request)

    val pageableSlot = slot<Pageable>()
    verify {
      txnRepo.findTransactionsWithFilters(any(), any(), any(), any(), any(), capture(pageableSlot))
    }
    assertEquals(TransactionQueryLimits.MAX_LIMIT, pageableSlot.captured.pageSize)
  }

  @Test
  fun `findTransactions passes kind filter to query`() {
    val request =
      GetTransactionsRequest.builder().assetCode("USDC").kind("deposit").limit(10).build()

    every { txnRepo.findTransactionsWithFilters(any(), any(), any(), any(), any(), any()) } returns
      emptyList()

    store.findTransactions("GACCOUNT", null, request)

    verify {
      txnRepo.findTransactionsWithFilters(
        eq("GACCOUNT"),
        eq("USDC"),
        eq("deposit"),
        any(),
        any(),
        any()
      )
    }
  }

  @Test
  fun `findTransactions with accountMemo uses memo query`() {
    val request = GetTransactionsRequest.builder().assetCode("USDC").limit(10).build()

    every {
      txnRepo.findTransactionsWithMemoAndFilters(any(), any(), any(), any(), any(), any(), any())
    } returns listOf(JdbcSep6Transaction())

    store.findTransactions("GACCOUNT", "12345", request)

    verify {
      txnRepo.findTransactionsWithMemoAndFilters(
        eq("GACCOUNT"),
        eq("12345"),
        eq("USDC"),
        any(),
        any(),
        any(),
        any()
      )
    }
  }

  @Test
  fun `findTransactions with paging_id resolves olderThan from transaction`() {
    val pagingTxn = JdbcSep6Transaction()
    val pagingTime = Instant.parse("2024-06-15T12:00:00Z")
    pagingTxn.startedAt = pagingTime

    every { txnRepo.findOneByTransactionId("paging-txn-id") } returns pagingTxn
    every { txnRepo.findTransactionsWithFilters(any(), any(), any(), any(), any(), any()) } returns
      emptyList()

    val request =
      GetTransactionsRequest.builder().assetCode("USDC").limit(10).pagingId("paging-txn-id").build()
    store.findTransactions("GACCOUNT", null, request)

    val olderThanSlot = slot<Instant>()
    verify {
      txnRepo.findTransactionsWithFilters(any(), any(), any(), any(), capture(olderThanSlot), any())
    }
    assertEquals(pagingTime, olderThanSlot.captured)
  }

  @Test
  fun `findTransactions with invalid paging_id throws SepValidationException`() {
    every { txnRepo.findOneByTransactionId("bad-id") } returns null

    val request = GetTransactionsRequest.builder().assetCode("USDC").pagingId("bad-id").build()

    assertThrows<SepValidationException> { store.findTransactions("GACCOUNT", null, request) }
  }

  @Test
  fun `findTransactions with invalid noOlderThan throws SepValidationException`() {
    val request =
      GetTransactionsRequest.builder().assetCode("USDC").noOlderThan("not-a-date").build()

    assertThrows<SepValidationException> { store.findTransactions("GACCOUNT", null, request) }
  }
}
