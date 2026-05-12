package org.stellar.anchor.platform.data

import io.mockk.MockKAnnotations
import io.mockk.every
import io.mockk.impl.annotations.MockK
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class JdbcSep38QuoteStoreTest {

  @MockK(relaxed = true) private lateinit var quoteRepo: JdbcSep38QuoteRepo

  private lateinit var store: JdbcSep38QuoteStore

  @BeforeEach
  fun setUp() {
    MockKAnnotations.init(this, relaxUnitFun = true)
    store = JdbcSep38QuoteStore(quoteRepo)
  }

  @Test
  fun `bindToTransaction returns true when conditional UPDATE affects one row`() {
    every { quoteRepo.bindToTransaction("q1", "txn1") } returns 1
    assertTrue(store.bindToTransaction("q1", "txn1"))
  }

  @Test
  fun `bindToTransaction returns false when conditional UPDATE affects zero rows`() {
    every { quoteRepo.bindToTransaction("q1", "txn2") } returns 0
    assertFalse(store.bindToTransaction("q1", "txn2"))
  }

  @Test
  fun `bindToTransaction returns true on first call then false on second call`() {
    every { quoteRepo.bindToTransaction("q1", any()) } returnsMany listOf(1, 0)
    assertTrue(store.bindToTransaction("q1", "txn1"))
    assertFalse(store.bindToTransaction("q1", "txn2"))
  }
}
