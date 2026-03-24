package org.stellar.anchor.api.custody.fireblocks;

import lombok.Data;

@Data
public class AmountInfo {
  private String amount;
  private String requestedAmount;
  private String netAmount;
  private String amountUSD;
}
