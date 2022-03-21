package org.stellar.anchor.sep38;

import org.stellar.anchor.exception.NotFoundException;
import org.stellar.anchor.exception.SepException;
import org.stellar.anchor.model.Sep38Quote;
import reactor.util.annotation.NonNull;

/** This interface is for the SEP adapter service to query/save the quote document. */
public interface Sep38QuoteStore {
  Sep38Quote newInstance();

  /**
   * Find the Sep38Quote by quote_id
   *
   * @param quoteId The quote ID
   * @return The quote document. null if not found.
   * @throws SepException if error happens
   */
  Sep38Quote findByQuoteId(@NonNull String quoteId) throws SepException, NotFoundException;

  /**
   * Save a quote.
   *
   * @param sep38Quote The quote to be saved.
   * @return The saved quote.
   * @throws SepException if error happens
   */
  @SuppressWarnings("UnusedReturnValue")
  Sep38Quote save(Sep38Quote sep38Quote) throws SepException;
}
