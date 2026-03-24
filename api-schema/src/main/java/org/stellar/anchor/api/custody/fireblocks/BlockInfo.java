package org.stellar.anchor.api.custody.fireblocks;

import lombok.Data;

@Data
public class BlockInfo {
  private String blockHeight;
  private String blockHash;
}
