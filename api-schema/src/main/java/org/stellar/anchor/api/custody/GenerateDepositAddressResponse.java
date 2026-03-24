package org.stellar.anchor.api.custody;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class GenerateDepositAddressResponse {

  private String address;
  private String memo;
  private String memoType;
}
