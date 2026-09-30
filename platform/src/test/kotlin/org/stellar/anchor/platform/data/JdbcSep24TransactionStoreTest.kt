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
import org.stellar.anchor.api.sep.sep24.GetTransactionsRequest
import org.stellar.anchor.util.TransactionQueryLimits

class JdbcSep24TransactionStoreTest {

  @MockK(relaxed = true) private lateinit var txnRepo: JdbcSep24TransactionRepo

  private lateinit var store: JdbcSep24TransactionStore

  @BeforeEach
  fun setUp() {
    MockKAnnotations.init(this, relaxUnitFun = true)
    store = JdbcSep24TransactionStore(txnRepo)
  }

  @Test
  fun `findTransactions uses database-level pagination with default limit`() {
    val request = GetTransactionsRequest.of("USDC", null, null, null, null, null)

    every {
      txnRepo.findTransactionsWithFilters(any(), any(), any(), any(), any(), any(), any())
    } returns emptyList()

    store.findTransactions("GACCOUNT", null, "USDC", null, request)

    val pageableSlot = slot<Pageable>()
    verify {
      txnRepo.findTransactionsWithFilters(
        eq("GACCOUNT"),
        eq("USDC"),
        isNull(),
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
  fun `findTransactions respects provided limit`() {
    val request = GetTransactionsRequest.of("USDC", null, 50, null, null, null)

    every {
      txnRepo.findTransactionsWithFilters(any(), any(), any(), any(), any(), any(), any())
    } returns emptyList()

    store.findTransactions("GACCOUNT", null, "USDC", null, request)

    val pageableSlot = slot<Pageable>()
    verify {
      txnRepo.findTransactionsWithFilters(
        any(),
        any(),
        any(),
        any(),
        any(),
        any(),
        capture(pageableSlot)
      )
    }
    assertEquals(50, pageableSlot.captured.pageSize)
  }

  @Test
  fun `findTransactions caps limit at MAX_LIMIT`() {
    val request = GetTransactionsRequest.of("USDC", null, 50000, null, null, null)

    every {
      txnRepo.findTransactionsWithFilters(any(), any(), any(), any(), any(), any(), any())
    } returns emptyList()

    store.findTransactions("GACCOUNT", null, "USDC", null, request)

    val pageableSlot = slot<Pageable>()
    verify {
      txnRepo.findTransactionsWithFilters(
        any(),
        any(),
        any(),
        any(),
        any(),
        any(),
        capture(pageableSlot)
      )
    }
    assertEquals(TransactionQueryLimits.MAX_LIMIT, pageableSlot.captured.pageSize)
  }

  @Test
  fun `findTransactions passes kind filter to query`() {
    val request = GetTransactionsRequest.of("USDC", "deposit", 10, null, null, null)

    every {
      txnRepo.findTransactionsWithFilters(any(), any(), any(), any(), any(), any(), any())
    } returns emptyList()

    store.findTransactions("GACCOUNT", null, "USDC", null, request)

    verify {
      txnRepo.findTransactionsWithFilters(
        eq("GACCOUNT"),
        eq("USDC"),
        isNull(),
        eq("deposit"),
        any(),
        any(),
        any()
      )
    }
  }

  @Test
  fun `findTransactions passes date filters to query`() {
    val request = GetTransactionsRequest.of("USDC", null, 10, "2024-01-01T00:00:00Z", null, null)

    every {
      txnRepo.findTransactionsWithFilters(any(), any(), any(), any(), any(), any(), any())
    } returns emptyList()

    store.findTransactions("GACCOUNT", null, "USDC", null, request)

    val noOlderThanSlot = slot<Instant>()
    verify {
      txnRepo.findTransactionsWithFilters(
        any(),
        any(),
        any(),
        any(),
        capture(noOlderThanSlot),
        any(),
        any()
      )
    }
    assertEquals(Instant.parse("2024-01-01T00:00:00Z"), noOlderThanSlot.captured)
  }

  @Test
  fun `findTransactions with accountMemo uses memo query`() {
    val request = GetTransactionsRequest.of("USDC", null, 10, null, null, null)

    every {
      txnRepo.findTransactionsWithMemoAndFilters(
        any(),
        any(),
        any(),
        any(),
        any(),
        any(),
        any(),
        any()
      )
    } returns listOf(JdbcSep24Transaction())

    store.findTransactions("GACCOUNT", "12345", "USDC", null, request)

    verify {
      txnRepo.findTransactionsWithMemoAndFilters(
        eq("GACCOUNT"),
        eq("12345"),
        eq("USDC"),
        isNull(),
        any(),
        any(),
        any(),
        any()
      )
    }
  }

  @Test
  fun `findTransactions with accountMemo falls back to legacy format when empty`() {
    val request = GetTransactionsRequest.of("USDC", null, 10, null, null, null)

    every {
      txnRepo.findTransactionsWithMemoAndFilters(
        any(),
        any(),
        any(),
        any(),
        any(),
        any(),
        any(),
        any()
      )
    } returns emptyList()

    every {
      txnRepo.findTransactionsWithFilters(any(), any(), any(), any(), any(), any(), any())
    } returns emptyList()

    store.findTransactions("GACCOUNT", "12345", "USDC", null, request)

    // First tries with memo
    verify {
      txnRepo.findTransactionsWithMemoAndFilters(
        eq("GACCOUNT"),
        eq("12345"),
        eq("USDC"),
        isNull(),
        any(),
        any(),
        any(),
        any()
      )
    }
    // Falls back to legacy account:memo format
    verify {
      txnRepo.findTransactionsWithFilters(
        eq("GACCOUNT:12345"),
        eq("USDC"),
        isNull(),
        any(),
        any(),
        any(),
        any()
      )
    }
  }

  @Test
  fun `findTransactions with paging_id resolves olderThan from transaction`() {
    val pagingTxn = JdbcSep24Transaction()
    val pagingTime = Instant.parse("2024-06-15T12:00:00Z")
    pagingTxn.startedAt = pagingTime
    pagingTxn.webAuthAccount = "GACCOUNT"

    every { txnRepo.findOneByTransactionId("paging-txn-id") } returns pagingTxn
    every {
      txnRepo.findTransactionsWithFilters(any(), any(), any(), any(), any(), any(), any())
    } returns emptyList()

    val request = GetTransactionsRequest.of("USDC", null, 10, null, "paging-txn-id", null)
    store.findTransactions("GACCOUNT", null, "USDC", null, request)

    val olderThanSlot = slot<Instant>()
    verify {
      txnRepo.findTransactionsWithFilters(
        any(),
        any(),
        any(),
        any(),
        any(),
        capture(olderThanSlot),
        any()
      )
    }
    assertEquals(pagingTime, olderThanSlot.captured)
  }

  @Test
  fun `findTransactions with invalid noOlderThan throws SepValidationException`() {
    val request = GetTransactionsRequest.of("USDC", null, 10, "not-a-date", null, null)

    assertThrows<SepValidationException> {
      store.findTransactions("GACCOUNT", null, "USDC", null, request)
    }
  }

  @Test
  fun `findTransactions with zero or negative limit uses default`() {
    val request = GetTransactionsRequest.of("USDC", null, 0, null, null, null)

    every {
      txnRepo.findTransactionsWithFilters(any(), any(), any(), any(), any(), any(), any())
    } returns emptyList()

    store.findTransactions("GACCOUNT", null, "USDC", null, request)

    val pageableSlot = slot<Pageable>()
    verify {
      txnRepo.findTransactionsWithFilters(
        any(),
        any(),
        any(),
        any(),
        any(),
        any(),
        capture(pageableSlot)
      )
    }
    assertEquals(TransactionQueryLimits.DEFAULT_LIMIT, pageableSlot.captured.pageSize)
  }

  @Test
  fun `findTransactions forwards the issuer and ignores the raw asset code of the request`() {
    // The service hands over the parsed code and issuer; the request still carries the raw form.
    val request = GetTransactionsRequest.of("stellar:USDC:GISSUER", null, 10, null, null, null)

    every {
      txnRepo.findTransactionsWithFilters(any(), any(), any(), any(), any(), any(), any())
    } returns emptyList()

    store.findTransactions("GACCOUNT", null, "USDC", "GISSUER", request)

    verify {
      txnRepo.findTransactionsWithFilters(
        eq("GACCOUNT"),
        eq("USDC"),
        eq("GISSUER"),
        any(),
        any(),
        any(),
        any()
      )
    }
  }

  @Test
  fun `findTransactions forwards the issuer to the memo query and its legacy fallback`() {
    val request = GetTransactionsRequest.of("USDC", null, 10, null, null, null)

    every {
      txnRepo.findTransactionsWithMemoAndFilters(
        any(),
        any(),
        any(),
        any(),
        any(),
        any(),
        any(),
        any()
      )
    } returns emptyList()
    every {
      txnRepo.findTransactionsWithFilters(any(), any(), any(), any(), any(), any(), any())
    } returns emptyList()

    store.findTransactions("GACCOUNT", "12345", "USDC", "GISSUER", request)

    verify {
      txnRepo.findTransactionsWithMemoAndFilters(
        eq("GACCOUNT"),
        eq("12345"),
        eq("USDC"),
        eq("GISSUER"),
        any(),
        any(),
        any(),
        any()
      )
    }
    verify {
      txnRepo.findTransactionsWithFilters(
        eq("GACCOUNT:12345"),
        eq("USDC"),
        eq("GISSUER"),
        any(),
        any(),
        any(),
        any()
      )
    }
  }

  private fun pagingTxn(account: String?, memo: String?) =
    JdbcSep24Transaction().apply {
      webAuthAccount = account
      webAuthAccountMemo = memo
      startedAt = Instant.parse("2024-06-15T12:00:00Z")
    }

  private fun assertPagingRejected(accountId: String, accountMemo: String?) {
    val request = GetTransactionsRequest.of("USDC", null, 10, null, "paging-txn-id", null)

    val ex =
      assertThrows<SepValidationException> {
        store.findTransactions(accountId, accountMemo, "USDC", null, request)
      }

    assertEquals("invalid paging_id field: paging-txn-id", ex.message)
  }

  @Test
  fun `findTransactions with paging_id accepts the legacy account-memo row for its memo caller`() {
    every { txnRepo.findOneByTransactionId("paging-txn-id") } returns
      pagingTxn("GACCOUNT:12345", null)
    every {
      txnRepo.findTransactionsWithMemoAndFilters(
        any(),
        any(),
        any(),
        any(),
        any(),
        any(),
        any(),
        any()
      )
    } returns emptyList()
    every {
      txnRepo.findTransactionsWithFilters(any(), any(), any(), any(), any(), any(), any())
    } returns emptyList()
    val request = GetTransactionsRequest.of("USDC", null, 10, null, "paging-txn-id", null)

    store.findTransactions("GACCOUNT", "12345", "USDC", null, request)

    val olderThanSlot = slot<Instant>()
    verify {
      txnRepo.findTransactionsWithMemoAndFilters(
        any(),
        any(),
        any(),
        any(),
        any(),
        any(),
        capture(olderThanSlot),
        any()
      )
    }
    assertEquals(Instant.parse("2024-06-15T12:00:00Z"), olderThanSlot.captured)
  }

  @Test
  fun `findTransactions with paging_id rejects another account's transaction like a missing one`() {
    every { txnRepo.findOneByTransactionId("paging-txn-id") } returns pagingTxn("GOTHER", null)

    assertPagingRejected("GACCOUNT", null)
  }

  @Test
  fun `findTransactions with paging_id rejects another memo's transaction on the same account`() {
    every { txnRepo.findOneByTransactionId("paging-txn-id") } returns pagingTxn("GACCOUNT", "99999")
    assertPagingRejected("GACCOUNT", "12345")

    // a memo-less caller must not page a memo-scoped row either
    assertPagingRejected("GACCOUNT", null)
  }

  @Test
  fun `findTransactions with paging_id rejects a G-stored transaction for a muxed caller`() {
    every { txnRepo.findOneByTransactionId("paging-txn-id") } returns pagingTxn("GACCOUNT", null)

    assertPagingRejected("MMUXEDACCOUNT", null)
  }

  @Test
  fun `findTransactions with paging_id rejects a transaction that does not exist`() {
    every { txnRepo.findOneByTransactionId("paging-txn-id") } returns null

    assertPagingRejected("GACCOUNT", null)
  }
}
