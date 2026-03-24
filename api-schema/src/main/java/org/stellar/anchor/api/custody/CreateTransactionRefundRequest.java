package org.stellar.anchor.api.custody;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class CreateTransactionRefundRequest {
  private String memo;
  private String memoType;
  private String amount;
  private String amountFee;
}
