package org.stellar.anchor.platform.config;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
public class RpcConfig {
  private CustomMessages customMessages;
  private int batchSizeLimit;

  /**
   * Whether a payment that reaches the ledger at or after its firm quote's expiry is honored at the
   * quoted amounts. When false (the default) the transaction is held in the error status.
   */
  private boolean honorLateQuotePayments;

  @Data
  @AllArgsConstructor
  @NoArgsConstructor
  public static class CustomMessages {
    private String incomingPaymentReceived;
    private String outgoingPaymentSent;
  }
}
