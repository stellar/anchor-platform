package org.stellar.anchor.api.custody.fireblocks;

import lombok.Data;

@Data
public class AmlScreeningResult {
  private String provider;
  private String payload;
}
