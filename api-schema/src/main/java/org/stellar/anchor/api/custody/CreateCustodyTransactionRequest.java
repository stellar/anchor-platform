package org.stellar.anchor.api.custody;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class CreateCustodyTransactionRequest {

  private String id;
  private String memo;
  private String memoType;
  private String protocol;
  private String fromAccount;
  private String toAccount;
  private String amount;
  private String amountFee;
  private String asset;
  private String kind;
}
