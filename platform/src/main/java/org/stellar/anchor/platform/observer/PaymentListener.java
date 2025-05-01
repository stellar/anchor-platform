package org.stellar.anchor.platform.observer;

import java.io.IOException;
import org.stellar.anchor.api.exception.AnchorException;
import org.stellar.anchor.ledger.PaymentTransferEvent;

public interface PaymentListener {
  void onReceived(PaymentTransferEvent paymentTransferEvent) throws AnchorException, IOException;
}
