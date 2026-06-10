package org.stellar.anchor.util;

/**
 * Shared constants for bounding transaction list queries across SEP-6 and SEP-24.
 *
 * <p>These limits apply to the client-facing SEP API endpoints (e.g. GET /sep24/transactions, GET
 * /sep6/transactions) which use cursor-based pagination via {@code paging_id}.
 *
 * <p>Not to be confused with {@link TransactionsParams}, which provides offset-based pagination
 * (pageNumber/pageSize) for the internal Platform API (GET /transactions).
 */
public final class TransactionQueryLimits {
  private TransactionQueryLimits() {}

  /** Default number of transactions returned when no limit is specified. */
  public static final int DEFAULT_LIMIT = 100;

  /** Maximum number of transactions that can be returned in a single query. */
  public static final int MAX_LIMIT = 1000;
}
