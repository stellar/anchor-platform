package org.stellar.anchor.platform.job

import io.mockk.MockKAnnotations
import io.mockk.confirmVerified
import io.mockk.every
import io.mockk.impl.annotations.MockK
import io.mockk.verify
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import kotlin.test.Test
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.assertThrows
import org.stellar.anchor.auth.NonceStore

class NonceCleanupJobTest {

  @MockK(relaxed = true) private lateinit var nonceStore: NonceStore
  private lateinit var nonceCleanupJob: NonceCleanupJob

  private val now = Instant.parse("2026-11-12T10:34:58Z")

  @BeforeEach
  fun setup() {
    MockKAnnotations.init(this, relaxUnitFun = true)
    // The clock's own zone is deliberately not UTC: the instant handed to the store must not
    // depend on it.
    nonceCleanupJob = NonceCleanupJob(nonceStore, Clock.fixed(now, ZoneId.of("Europe/Berlin")))
  }

  @Test
  fun test_cleanup() {
    nonceCleanupJob.cleanup()

    verify(exactly = 1) { nonceStore.deleteExpiredNonces(now) }
    confirmVerified(nonceStore)
  }

  @Test
  fun `cleanup lets a store failure propagate to the scheduler`() {
    val failure = IllegalStateException("database unavailable")
    every { nonceStore.deleteExpiredNonces(now) } throws failure

    val thrown = assertThrows<IllegalStateException> { nonceCleanupJob.cleanup() }

    assertSame(failure, thrown)
  }
}
