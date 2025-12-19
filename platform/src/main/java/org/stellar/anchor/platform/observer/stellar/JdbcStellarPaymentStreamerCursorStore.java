package org.stellar.anchor.platform.observer.stellar;

import java.util.Optional;
import org.stellar.anchor.platform.data.PaymentStreamerCursor;
import org.stellar.anchor.platform.data.PaymentStreamerCursorRepo;

public class JdbcStellarPaymentStreamerCursorStore implements StellarPaymentStreamerCursorStore {
  private final PaymentStreamerCursorRepo repo;

  public JdbcStellarPaymentStreamerCursorStore(PaymentStreamerCursorRepo repo) {
    this.repo = repo;
  }

  @Override
  public void saveHorizonCursor(String cursor) {
    PaymentStreamerCursor paymentStreamerCursor =
        this.repo.findById(PaymentStreamerCursor.SINGLETON_ID).orElse(null);
    if (paymentStreamerCursor == null) {
      paymentStreamerCursor = new PaymentStreamerCursor();
    }

    paymentStreamerCursor.setId(PaymentStreamerCursor.SINGLETON_ID);
    paymentStreamerCursor.setHorizonCursor(cursor);

    this.repo.save(paymentStreamerCursor);
  }

  @Override
  public String loadHorizonCursor() {
    Optional<PaymentStreamerCursor> pageToken = repo.findById(PaymentStreamerCursor.SINGLETON_ID);
    return pageToken.map(PaymentStreamerCursor::getHorizonCursor).orElse(null);
  }

  @Override
  public void saveStellarRpcCursor(String cursor) {
    PaymentStreamerCursor paymentStreamerCursor =
        this.repo.findById(PaymentStreamerCursor.SINGLETON_ID).orElse(null);
    if (paymentStreamerCursor == null) {
      paymentStreamerCursor = new PaymentStreamerCursor();
    }

    paymentStreamerCursor.setId(PaymentStreamerCursor.SINGLETON_ID);
    paymentStreamerCursor.setStellarRpcCursor(cursor);

    this.repo.save(paymentStreamerCursor);
  }

  @Override
  public String loadStellarRpcCursor() {
    Optional<PaymentStreamerCursor> pageToken = repo.findById(PaymentStreamerCursor.SINGLETON_ID);
    return pageToken.map(PaymentStreamerCursor::getStellarRpcCursor).orElse(null);
  }
}
