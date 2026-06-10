package org.stellar.anchor.platform.data;

import lombok.NonNull;
import org.stellar.anchor.api.exception.SepException;
import org.stellar.anchor.sep38.Sep38Quote;
import org.stellar.anchor.sep38.Sep38QuoteStore;

public class JdbcSep38QuoteStore implements Sep38QuoteStore {
  private final JdbcSep38QuoteRepo quoteRepo;

  public JdbcSep38QuoteStore(JdbcSep38QuoteRepo quoteRepo) {
    this.quoteRepo = quoteRepo;
  }

  @Override
  public Sep38Quote newInstance() {
    return new JdbcSep38Quote();
  }

  @Override
  public Sep38Quote findByQuoteId(@NonNull String quoteId) {
    return quoteRepo.findById(quoteId).orElse(null);
  }

  @Override
  public Sep38Quote save(Sep38Quote sep38Quote) throws SepException {
    if (!(sep38Quote instanceof JdbcSep38Quote)) {
      throw new SepException(
          sep38Quote.getClass() + "  is not a sub-type of " + JdbcSep38Quote.class);
    }
    return quoteRepo.save((JdbcSep38Quote) sep38Quote);
  }

  @Override
  public boolean bindToTransaction(@NonNull String quoteId, @NonNull String transactionId) {
    return quoteRepo.bindToTransaction(quoteId, transactionId) > 0;
  }
}
