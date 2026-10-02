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
    pagingTxn.webAuthAccount = "GACCOUNT"

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

  private fun pagingRow(owner: String?, memo: String?, startedAt: Instant): JdbcSep6Transaction =
    JdbcSep6Transaction().apply {
      webAuthAccount = owner
      webAuthAccountMemo = memo
      this.startedAt = startedAt
    }

  /**
   * The caller's `accountId`/`accountMemo` page with `row`; returns the `olderThan` it was given.
   */
  private fun pageWith(row: JdbcSep6Transaction, accountId: String, accountMemo: String?): Instant {
    every { txnRepo.findOneByTransactionId("paging-txn-id") } returns row
    every { txnRepo.findTransactionsWithFilters(any(), any(), any(), any(), any(), any()) } returns
      emptyList()
    every {
      txnRepo.findTransactionsWithMemoAndFilters(any(), any(), any(), any(), any(), any(), any())
    } returns emptyList()

    val request =
      GetTransactionsRequest.builder().assetCode("USDC").pagingId("paging-txn-id").build()
    store.findTransactions(accountId, accountMemo, request)

    val olderThanSlot = slot<Instant>()
    if (accountMemo == null) {
      verify {
        txnRepo.findTransactionsWithFilters(
          any(),
          any(),
          any(),
          any(),
          capture(olderThanSlot),
          any()
        )
      }
    } else {
      verify {
        txnRepo.findTransactionsWithMemoAndFilters(
          any(),
          any(),
          any(),
          any(),
          any(),
          capture(olderThanSlot),
          any()
        )
      }
    }
    return olderThanSlot.captured
  }

  /** Every ownership mismatch must answer exactly like a nonexistent id, and run no listing. */
  private fun assertPagingRejected(
    row: JdbcSep6Transaction?,
    accountId: String,
    accountMemo: String?
  ) {
    every { txnRepo.findOneByTransactionId("paging-txn-id") } returns row

    val request =
      GetTransactionsRequest.builder().assetCode("USDC").pagingId("paging-txn-id").build()
    val ex =
      assertThrows<SepValidationException> {
        store.findTransactions(accountId, accountMemo, request)
      }

    assertEquals("invalid paging_id field: paging-txn-id", ex.message)
    verify(exactly = 0) {
      txnRepo.findTransactionsWithFilters(any(), any(), any(), any(), any(), any())
    }
    verify(exactly = 0) {
      txnRepo.findTransactionsWithMemoAndFilters(any(), any(), any(), any(), any(), any(), any())
    }
  }

  @Test
  fun `findTransactions pages from an own no-memo row`() {
    val time = Instant.parse("2024-06-15T12:00:00Z")

    assertEquals(time, pageWith(pagingRow("GACCOUNT", null, time), "GACCOUNT", null))
  }

  @Test
  fun `findTransactions pages from an own memo row`() {
    val time = Instant.parse("2024-06-16T12:00:00Z")

    assertEquals(time, pageWith(pagingRow("GACCOUNT", "111", time), "GACCOUNT", "111"))
  }

  @Test
  fun `findTransactions pages from an own muxed row`() {
    val time = Instant.parse("2024-06-17T12:00:00Z")

    assertEquals(time, pageWith(pagingRow("MMUXED", null, time), "MMUXED", null))
  }

  @Test
  fun `findTransactions rejects a paging_id owned by a different account`() {
    assertPagingRejected(pagingRow("GOTHER", null, Instant.now()), "GACCOUNT", null)
  }

  @Test
  fun `findTransactions rejects a paging_id owned by a different memo`() {
    assertPagingRejected(pagingRow("GACCOUNT", "111", Instant.now()), "GACCOUNT", "222")
  }

  @Test
  fun `findTransactions rejects a memo row for a caller without a memo`() {
    assertPagingRejected(pagingRow("GACCOUNT", "111", Instant.now()), "GACCOUNT", null)
  }

  @Test
  fun `findTransactions rejects a no-memo row for a caller with a memo`() {
    assertPagingRejected(pagingRow("GACCOUNT", null, Instant.now()), "GACCOUNT", "111")
  }

  @Test
  fun `findTransactions rejects a G row for a muxed caller`() {
    assertPagingRejected(pagingRow("GACCOUNT", null, Instant.now()), "MMUXED", null)
  }

  @Test
  fun `findTransactions rejects a muxed row for a G caller`() {
    assertPagingRejected(pagingRow("MMUXED", null, Instant.now()), "GACCOUNT", null)
  }

  @Test
  fun `findTransactions rejects a nonexistent paging_id with the same message`() {
    assertPagingRejected(null, "GACCOUNT", null)
  }

  @Test
  fun `findTransactions with invalid noOlderThan throws SepValidationException`() {
    val request =
      GetTransactionsRequest.builder().assetCode("USDC").noOlderThan("not-a-date").build()

    assertThrows<SepValidationException> { store.findTransactions("GACCOUNT", null, request) }
  }
}
