package org.stellar.anchor.config;

public interface SecretConfig {
  String getSep6MoreInfoUrlJwtSecret();

  String getSep10JwtSecretKey();

  String getSep45JwtSecretKey();

  String getSep10SigningSeed();

  String getSep24InteractiveUrlJwtSecret();

  String getSep24MoreInfoUrlJwtSecret();

  String getCallbackAuthSecret();

  String getPlatformAuthSecret();

  String getDataSourceUsername();

  String getDataSourcePassword();

  String getEventsQueueKafkaUsername();

  String getEventsQueueKafkaPassword();

  String getRpcAuthSecret();
}
