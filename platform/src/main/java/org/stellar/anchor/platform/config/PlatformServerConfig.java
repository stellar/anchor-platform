package org.stellar.anchor.platform.config;

import lombok.Data;

@Data
public class PlatformServerConfig {
  String contextPath;
  long maxRequestBodySize;
  int maxPatchRecords;
  PropertySecretConfig secretConfig;

  public PlatformServerConfig(PropertySecretConfig secretConfig) {
    this.secretConfig = secretConfig;
  }
}
