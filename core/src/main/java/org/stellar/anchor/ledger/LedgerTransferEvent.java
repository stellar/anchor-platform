package org.stellar.anchor.ledger;

import java.util.List;
import lombok.Builder;
import lombok.Data;
import lombok.Getter;
import lombok.experimental.SuperBuilder;

@Data
@Builder
public class LedgerTransferEvent {
  SingleOpLedgerTransaction ledgerTransaction;

  @SuperBuilder
  @Getter
  public static class SingleOpLedgerTransaction extends LedgerTransaction {
    LedgerOperation operation;

    @Override
    public List<LedgerOperation> getOperations() {
      throw new UnsupportedOperationException(
          "SingleOpLedgerTransaction does not support getOperations()");
    }
  }
}
