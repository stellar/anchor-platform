package org.stellar.anchor.config;

public interface SecretConfig {
  String getSep6MoreInfoUrlJwtSecret();

  @Deprecated // TODO: ANCHOR-667
  String getSep10JwtSecretKey();

  @Deprecated // TODO: ANCHOR-667
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
}
