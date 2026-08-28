package org.stellar.anchor.platform.data

import io.mockk.MockKAnnotations
import io.mockk.every
import io.mockk.impl.annotations.MockK
import io.mockk.verify
import java.time.Instant
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

  private fun nonce(id: String, expiresAt: Instant) =
    JdbcNonce().apply {
      this.id = id
      this.expiresAt = expiresAt
    }

  @Test
  fun `insertIfAbsent returns true when the row is newly inserted`() {
    val expiresAt = Instant.parse("2026-08-27T00:00:00Z")
    every { repo.insertIfAbsent("nonce-1", expiresAt) } returns 1

    assertTrue(store.insertIfAbsent(nonce("nonce-1", expiresAt)))
    verify(exactly = 1) { repo.insertIfAbsent("nonce-1", expiresAt) }
  }

  @Test
  fun `insertIfAbsent returns false when the id already exists`() {
    val expiresAt = Instant.parse("2026-08-27T00:00:00Z")
    every { repo.insertIfAbsent("nonce-1", expiresAt) } returns 0

    assertFalse(store.insertIfAbsent(nonce("nonce-1", expiresAt)))
    verify(exactly = 1) { repo.insertIfAbsent("nonce-1", expiresAt) }
  }
}
