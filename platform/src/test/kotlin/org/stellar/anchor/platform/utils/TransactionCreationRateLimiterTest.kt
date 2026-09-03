package org.stellar.anchor.platform.utils

import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.stellar.anchor.api.exception.SepRateLimitExceededException
import org.stellar.anchor.auth.WebAuthJwt
import org.stellar.anchor.config.RateLimitConfig

class TransactionCreationRateLimiterTest {

  private fun tokenFor(account: String, memo: String? = null): WebAuthJwt {
    val token = mockk<WebAuthJwt>()
    every { token.ownerAccount } returns account
    every { token.ownerMemo } returns memo
    return token
  }

  @Test
  fun `does nothing when disabled`() {
    val config = mockk<RateLimitConfig>()
    every { config.isEnabled } returns false
    every { config.windowSeconds } returns 60

    val limiter = TransactionCreationRateLimiter(config)
    val token = tokenFor("GALICE")
    for (i in 1..1000) {
      limiter.checkAndRecord(token)
    }
  }

  @Test
  fun `allows requests up to the configured max and rejects beyond it`() {
    val config = mockk<RateLimitConfig>()
    every { config.isEnabled } returns true
    every { config.maxTransactionsPerWindow } returns 3
    every { config.windowSeconds } returns 60

    val limiter = TransactionCreationRateLimiter(config)
    val token = tokenFor("GALICE")

    limiter.checkAndRecord(token)
    limiter.checkAndRecord(token)
    limiter.checkAndRecord(token)
    assertThrows(SepRateLimitExceededException::class.java) { limiter.checkAndRecord(token) }
  }

  @Test
  fun `tracks different identities independently`() {
    val config = mockk<RateLimitConfig>()
    every { config.isEnabled } returns true
    every { config.maxTransactionsPerWindow } returns 1
    every { config.windowSeconds } returns 60

    val limiter = TransactionCreationRateLimiter(config)
    val alice = tokenFor("GALICE")
    val bob = tokenFor("GBOB")

    limiter.checkAndRecord(alice)
    assertThrows(SepRateLimitExceededException::class.java) { limiter.checkAndRecord(alice) }
    limiter.checkAndRecord(bob)
  }

  @Test
  fun `treats the same account with different memos as different identities`() {
    val config = mockk<RateLimitConfig>()
    every { config.isEnabled } returns true
    every { config.maxTransactionsPerWindow } returns 1
    every { config.windowSeconds } returns 60

    val limiter = TransactionCreationRateLimiter(config)
    val memo1 = tokenFor("GSHARED", "1")
    val memo2 = tokenFor("GSHARED", "2")

    limiter.checkAndRecord(memo1)
    assertThrows(SepRateLimitExceededException::class.java) { limiter.checkAndRecord(memo1) }
    limiter.checkAndRecord(memo2)
  }

  @Test
  fun `does nothing when token is null`() {
    val config = mockk<RateLimitConfig>()
    every { config.isEnabled } returns true
    every { config.maxTransactionsPerWindow } returns 1
    every { config.windowSeconds } returns 60

    val limiter = TransactionCreationRateLimiter(config)
    limiter.checkAndRecord(null)
    limiter.checkAndRecord(null)
  }
}
