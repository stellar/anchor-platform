package org.stellar.anchor.auth;

import java.time.Instant;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class PojoNonce implements Nonce {
  private String id;
  private Boolean used;
  private Instant expiresAt;
}
