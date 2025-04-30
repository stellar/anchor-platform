package org.stellar.anchor.ledger;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class PaymentTransferEvent {
  String from;
  String to;
  String sep11Asset;
  Long amount;
  String txHash;

  // The operationId is the TOID of the operation in the transaction.
  Long operationId;
  // The ledgerTransaction is not fetched until getTransaction() is called.
  LedgerTransaction ledgerTransaction;
}
