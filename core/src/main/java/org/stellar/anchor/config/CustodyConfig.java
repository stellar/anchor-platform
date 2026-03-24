package org.stellar.anchor.config;

import static org.stellar.anchor.config.CustodyConfig.CustodyType.NONE;

public interface CustodyConfig {

  default boolean isCustodyIntegrationEnabled() {
    return NONE != getType();
  }

  CustodyType getType();

  enum CustodyType {
    NONE("none"),
    FIREBLOCKS("fireblocks");

    private final String type;

    CustodyType(String type) {
      this.type = type;
    }

    public String toString() {
      return type;
    }
  }
}
