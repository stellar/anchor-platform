package org.stellar.anchor.platform.utils

import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class QuotePaymentTimeTest {
  private val expiresAt = Instant.parse("2026-11-19T12:43:39Z")

  @Test
  fun `a quote without an expiry is never late`() {
    assertFalse(QuotePaymentTime.isLate(null, expiresAt.plusSeconds(3600)))
  }

  @Test
  fun `a payment one nanosecond before the expiry is on time`() {
    assertFalse(QuotePaymentTime.isLate(expiresAt, expiresAt.minusNanos(1)))
  }

  @Test
  fun `a payment at the exact expiry is late`() {
    assertTrue(QuotePaymentTime.isLate(expiresAt, expiresAt))
  }

  @Test
  fun `a payment after the expiry is late`() {
    assertTrue(QuotePaymentTime.isLate(expiresAt, expiresAt.plusSeconds(29)))
  }

  @Test
  fun `the hold message names the quote, its expiry and the payment time`() {
    val paymentTime = Instant.parse("2026-11-19T12:44:08Z")

    assertEquals(
      "Payment received after the firm quote expired: quote q1 expired at " +
        "2026-11-19T12:43:39Z, payment ledger time 2026-11-19T12:44:08Z",
      QuotePaymentTime.lateMessage("q1", expiresAt, paymentTime)
    )
  }
}
