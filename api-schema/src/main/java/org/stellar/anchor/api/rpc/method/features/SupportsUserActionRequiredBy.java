package org.stellar.anchor.api.rpc.method.features;

import java.time.Instant;

public interface SupportsUserActionRequiredBy {
  Instant getUserActionRequiredBy();

  void setUserActionRequiredBy(Instant userActionRequiredBy);
}
