package org.stellar.anchor.platform.data

import io.mockk.MockKAnnotations
import io.mockk.every
import io.mockk.impl.annotations.MockK
import io.mockk.verify
import java.util.Optional
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class JdbcSep31CustomerIdOwnerStoreTest {

  @MockK(relaxed = true) private lateinit var repo: JdbcSep31CustomerIdOwnerRepo

  private lateinit var store: JdbcSep31CustomerIdOwnerStore

  @BeforeEach
  fun setUp() {
    MockKAnnotations.init(this, relaxUnitFun = true)
    store = JdbcSep31CustomerIdOwnerStore(repo)
  }

  private fun ownerRow(customerId: String, account: String, memo: String?) =
    JdbcSep31CustomerIdOwner().apply {
      this.customerId = customerId
      this.creatorAccount = account
      this.creatorMemo = memo
    }

  @Test
  fun `first claim of a customer id succeeds without a follow-up lookup`() {
    every { repo.claimIfAbsent("cust-1", "GALICE", "111") } returns 1

    assertTrue(store.verifyOrClaim("cust-1", "GALICE", "111"))
    verify(exactly = 1) { repo.claimIfAbsent("cust-1", "GALICE", "111") }
    verify(exactly = 0) { repo.findById(any()) }
  }

  @Test
  fun `repeat reference by the same creator succeeds`() {
    every { repo.claimIfAbsent("cust-1", "GALICE", "111") } returns 0
    every { repo.findById("cust-1") } returns Optional.of(ownerRow("cust-1", "GALICE", "111"))

    assertTrue(store.verifyOrClaim("cust-1", "GALICE", "111"))
    verify(exactly = 1) { repo.findById("cust-1") }
  }

  @Test
  fun `reference by a different creator is rejected`() {
    every { repo.claimIfAbsent("cust-1", "GMALLORY", null) } returns 0
    every { repo.findById("cust-1") } returns Optional.of(ownerRow("cust-1", "GALICE", "111"))

    assertFalse(store.verifyOrClaim("cust-1", "GMALLORY", null))
  }

  @Test
  fun `null memo is compared correctly on the conflict path`() {
    every { repo.claimIfAbsent("cust-1", "GALICE", null) } returns 0
    every { repo.findById("cust-1") } returns Optional.of(ownerRow("cust-1", "GALICE", null))

    assertTrue(store.verifyOrClaim("cust-1", "GALICE", null))
  }

  @Test
  fun `throws if the row is missing after a non-inserting claim`() {
    every { repo.claimIfAbsent("cust-1", "GALICE", "111") } returns 0
    every { repo.findById("cust-1") } returns Optional.empty()

    assertThrows(IllegalStateException::class.java) {
      store.verifyOrClaim("cust-1", "GALICE", "111")
    }
  }

  @Test
  fun `isClaimed reflects whether an owner row already exists, without claiming it`() {
    every { repo.existsById("cust-1") } returns true
    every { repo.existsById("cust-2") } returns false

    assertTrue(store.isClaimed("cust-1"))
    assertFalse(store.isClaimed("cust-2"))
    verify(exactly = 0) { repo.claimIfAbsent(any(), any(), any()) }
  }
}
