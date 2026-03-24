package org.stellar.anchor.api.custody.fireblocks;

import java.util.List;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class CreateTransactionResponse {
  private String id;
  private TransactionStatus status;
  private List<SystemMessageInfo> systemMessages;
}
