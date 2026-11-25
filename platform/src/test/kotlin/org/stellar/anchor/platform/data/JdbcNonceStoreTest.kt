package org.stellar.anchor.platform.data

import io.mockk.MockKAnnotations
import io.mockk.confirmVerified
import io.mockk.every
import io.mockk.impl.annotations.MockK
import io.mockk.verify
import java.time.Instant
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class JdbcNonceStoreTest {

  @MockK(relaxed = true) private lateinit var repo: JdbcNonceRepo

  private lateinit var store: JdbcNonceStore

  @BeforeEach
  fun setUp() {
    MockKAnnotations.init(this, relaxUnitFun = true)
    store = JdbcNonceStore(repo)
  }

  private fun nonce(id: String, used: Boolean, expiresAt: Instant) =
    JdbcNonce().apply {
      this.id = id
      this.used = used
      this.expiresAt = expiresAt
    }

  @Test
  fun `deleteExpiredNonces issues one delete bound to the given instant and nothing else`() {
    val now = Instant.parse("2026-11-12T10:34:58Z")
    every { repo.deleteExpired(now) } returns 3

    store.deleteExpiredNonces(now)

    verify(exactly = 1) { repo.deleteExpired(now) }
    confirmVerified(repo)
  }

  @Test
  fun `deleteExpiredNonces completes when no row is expired`() {
    val now = Instant.parse("2026-11-12T10:34:58Z")
    every { repo.deleteExpired(now) } returns 0

    assertDoesNotThrow { store.deleteExpiredNonces(now) }
    verify(exactly = 1) { repo.deleteExpired(now) }
    confirmVerified(repo)
  }

  @Test
  fun `insertIfAbsent returns true when the row is newly inserted`() {
    val expiresAt = Instant.parse("2026-08-27T00:00:00Z")
    every { repo.insertIfAbsent("nonce-1", false, expiresAt) } returns 1

    assertTrue(store.insertIfAbsent(nonce("nonce-1", false, expiresAt)))
    verify(exactly = 1) { repo.insertIfAbsent("nonce-1", false, expiresAt) }
  }

  @Test
  fun `insertIfAbsent returns false when the id already exists`() {
    val expiresAt = Instant.parse("2026-08-27T00:00:00Z")
    every { repo.insertIfAbsent("nonce-1", false, expiresAt) } returns 0

    assertFalse(store.insertIfAbsent(nonce("nonce-1", false, expiresAt)))
    verify(exactly = 1) { repo.insertIfAbsent("nonce-1", false, expiresAt) }
  }

  @Test
  fun `insertIfAbsent passes through a pre-used nonce for atomic first-claim semantics`() {
    val expiresAt = Instant.parse("2026-08-27T00:00:00Z")
    every { repo.insertIfAbsent("nonce-1", true, expiresAt) } returns 1

    assertTrue(store.insertIfAbsent(nonce("nonce-1", true, expiresAt)))
    verify(exactly = 1) { repo.insertIfAbsent("nonce-1", true, expiresAt) }
  }
}
