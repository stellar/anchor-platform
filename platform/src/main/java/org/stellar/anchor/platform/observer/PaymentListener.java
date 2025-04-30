package org.stellar.anchor.platform.observer;

import java.io.IOException;
import org.stellar.anchor.api.exception.AnchorException;
import org.stellar.anchor.ledger.LedgerTransaction;

public interface PaymentListener {
  void onReceived(LedgerTransaction ledgerTransaction) throws AnchorException, IOException;
}
