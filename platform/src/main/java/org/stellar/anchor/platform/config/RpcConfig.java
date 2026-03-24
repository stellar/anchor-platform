package org.stellar.anchor.platform.config;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
public class RpcConfig {
  private CustomMessages customMessages;
  private int batchSizeLimit;

  @Data
  @AllArgsConstructor
  @NoArgsConstructor
  public static class CustomMessages {
    private String custodyTransactionFailed;
    private String incomingPaymentReceived;
    private String outgoingPaymentSent;
  }
}
