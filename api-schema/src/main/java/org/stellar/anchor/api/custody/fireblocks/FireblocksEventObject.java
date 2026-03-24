package org.stellar.anchor.api.custody.fireblocks;

import lombok.Data;

@Data
public class FireblocksEventObject {
  private EventType type;
  private String tenantId;
  private Long timestamp;
  private TransactionDetails data;
}
