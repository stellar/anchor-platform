package org.stellar.anchor.api.custody.fireblocks;

import lombok.Data;

@Data
public class FeeInfo {
  private String networkFee;
  private String serviceFee;
}
