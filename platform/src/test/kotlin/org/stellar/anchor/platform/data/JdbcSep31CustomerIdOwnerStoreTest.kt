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
  fun `isClaimed is false for a missing id`() {
    every { repo.findById("cust-missing") } returns Optional.empty()

    assertFalse(store.isClaimed("cust-missing"))
  }

  @Test
  fun `isClaimed is true when a row exists`() {
    every { repo.findById("cust-1") } returns Optional.of(ownerRow("cust-1", "GALICE", "111"))

    assertTrue(store.isClaimed("cust-1"))
  }

  @Test
  fun `verify is false for a missing id`() {
    every { repo.findById("cust-missing") } returns Optional.empty()

    assertFalse(store.verify("cust-missing", "GALICE", "111"))
  }

  @Test
  fun `verify is true when account and memo both match`() {
    every { repo.findById("cust-1") } returns Optional.of(ownerRow("cust-1", "GALICE", "111"))

    assertTrue(store.verify("cust-1", "GALICE", "111"))
  }

  @Test
  fun `verify is false when the account does not match`() {
    every { repo.findById("cust-1") } returns Optional.of(ownerRow("cust-1", "GALICE", "111"))

    assertFalse(store.verify("cust-1", "GMALLORY", "111"))
  }

  @Test
  fun `verify is false when the memo does not match`() {
    every { repo.findById("cust-1") } returns Optional.of(ownerRow("cust-1", "GALICE", "111"))

    assertFalse(store.verify("cust-1", "GALICE", "222"))
  }

  @Test
  fun `verify is false when a null memo is compared against a non-null stored memo`() {
    every { repo.findById("cust-1") } returns Optional.of(ownerRow("cust-1", "GALICE", "111"))

    assertFalse(store.verify("cust-1", "GALICE", null))
  }

  @Test
  fun `verify is true when both the stored and requested memo are null`() {
    every { repo.findById("cust-1") } returns Optional.of(ownerRow("cust-1", "GALICE", null))

    assertTrue(store.verify("cust-1", "GALICE", null))
  }

  @Test
  fun `reconcileLegacyKey rewrites a row still keyed by the legacy value and confirms the new key`() {
    every {
      repo.reassignIfCreatorAccountMatches("cust-1", "vibrant", null, "vibrant:GALICE", "111")
    } returns 1
    every { repo.findById("cust-1") } returns
      Optional.of(ownerRow("cust-1", "vibrant:GALICE", "111"))

    assertTrue(store.reconcileLegacyKey("cust-1", "vibrant", null, "vibrant:GALICE", "111"))
    verify(exactly = 1) {
      repo.reassignIfCreatorAccountMatches("cust-1", "vibrant", null, "vibrant:GALICE", "111")
    }
  }

  @Test
  fun `reconcileLegacyKey returns true when the row already matches the new key`() {
    every {
      repo.reassignIfCreatorAccountMatches("cust-1", "vibrant", null, "vibrant:GALICE", "111")
    } returns 0
    every { repo.findById("cust-1") } returns
      Optional.of(ownerRow("cust-1", "vibrant:GALICE", "111"))

    assertTrue(store.reconcileLegacyKey("cust-1", "vibrant", null, "vibrant:GALICE", "111"))
  }

  @Test
  fun `reconcileLegacyKey returns false when the row belongs to a different owner`() {
    every {
      repo.reassignIfCreatorAccountMatches("cust-1", "vibrant", null, "vibrant:GATTACKER", "111")
    } returns 0
    every { repo.findById("cust-1") } returns
      Optional.of(ownerRow("cust-1", "vibrant:GVICTIM", "111"))

    assertFalse(store.reconcileLegacyKey("cust-1", "vibrant", null, "vibrant:GATTACKER", "111"))
  }

  @Test
  fun `reconcileLegacyKey threads the legacy memo through to the reassignment query`() {
    every {
      repo.reassignIfCreatorAccountMatches("cust-1", "vibrant", "111", "vibrant:GALICE", "111")
    } returns 1
    every { repo.findById("cust-1") } returns
      Optional.of(ownerRow("cust-1", "vibrant:GALICE", "111"))

    assertTrue(store.reconcileLegacyKey("cust-1", "vibrant", "111", "vibrant:GALICE", "111"))
    verify(exactly = 1) {
      repo.reassignIfCreatorAccountMatches("cust-1", "vibrant", "111", "vibrant:GALICE", "111")
    }
  }
}
