package org.stellar.anchor.platform.observer;

import java.io.IOException;
import org.stellar.anchor.api.exception.AnchorException;
import org.stellar.anchor.ledger.LedgerTransferEvent;

public interface PaymentListener {
  void onReceived(LedgerTransferEvent ledgerTransferEvent) throws AnchorException, IOException;
}
