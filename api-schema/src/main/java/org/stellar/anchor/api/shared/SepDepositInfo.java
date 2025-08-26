package org.stellar.anchor.api.shared;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class SepDepositInfo {
  private final String stellarAddress;
  private final String memo;
}
