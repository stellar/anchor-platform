package org.stellar.anchor.config;

public interface CustodySecretConfig {
  String getFireblocksApiKey();

  String getFireblocksSecretKey();

  String getCustodyAuthSecret();
}
