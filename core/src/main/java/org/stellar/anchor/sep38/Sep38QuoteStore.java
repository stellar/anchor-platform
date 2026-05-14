package org.stellar.anchor.sep38;

import lombok.NonNull;
import org.stellar.anchor.api.exception.SepException;

/** This interface is for the SEP adapter service to query/save the quote document. */
public interface Sep38QuoteStore {
  Sep38Quote newInstance();

  /**
   * Find the Sep38Quote by quote_id
   *
   * @param quoteId The quote ID
   * @return The quote document. null if not found.
   */
  Sep38Quote findByQuoteId(@NonNull String quoteId);

  /**
   * Save a quote.
   *
   * @param sep38Quote The quote to be saved.
   * @return The saved quote.
   * @throws SepException if error happens
   */
  @SuppressWarnings("UnusedReturnValue")
  Sep38Quote save(Sep38Quote sep38Quote) throws SepException;

  /**
   * Bind this quote to a transaction.
   *
   * @param quoteId The quote ID
   * @param transactionId The transaction ID to bind
   * @return true if the quote was bound, false if it was already bound to another transaction
   * @throws SepException if a storage error occurs
   */
  boolean bindToTransaction(@NonNull String quoteId, @NonNull String transactionId)
      throws SepException;
}
