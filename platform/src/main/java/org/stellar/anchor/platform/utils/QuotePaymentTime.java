package org.stellar.anchor.platform.utils;

import java.time.Instant;

/**
 * Compares the time a payment reached the ledger with the expiry of the firm quote bound to the
 * transaction. The comparison has the shape of the creation-time checks: a quote with no expiry
 * never expires, and a payment at the expiry itself is late.
 */
public final class QuotePaymentTime {
  private QuotePaymentTime() {}

  /** Returns true when the payment time is at or after the quote's expiry. */
  public static boolean isLate(Instant expiresAt, Instant paymentTime) {
    return expiresAt != null && !expiresAt.isAfter(paymentTime);
  }

  /** The message stored on a transaction held for a payment after its quote expired. */
  public static String lateMessage(String quoteId, Instant expiresAt, Instant paymentTime) {
    return String.format(
        "Payment received after the firm quote expired: quote %s expired at %s, payment ledger time %s",
        quoteId, expiresAt, paymentTime);
  }
}
