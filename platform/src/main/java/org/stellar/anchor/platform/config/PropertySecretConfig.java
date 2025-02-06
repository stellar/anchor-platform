package org.stellar.anchor.platform.config;

import org.stellar.anchor.config.SecretConfig;
import org.stellar.anchor.platform.configurator.SecretManager;

public class PropertySecretConfig implements SecretConfig {

  public static final String SECRET_SEP_6_MORE_INFO_URL_JWT_SECRET =
      "secret.sep6.more_info_url.jwt_secret";
  public static final String SECRET_SEP_10_JWT_SECRET = "secret.sep10.jwt_secret";
  public static final String SECRET_SEP_10_SIGNING_SEED = "secret.sep10.signing_seed";
  public static final String SECRET_SEP_24_INTERACTIVE_URL_JWT_SECRET =
      "secret.sep24.interactive_url.jwt_secret";
  public static final String SECRET_SEP_24_MORE_INFO_URL_JWT_SECRET =
      "secret.sep24.more_info_url.jwt_secret";
  public static final String SECRET_SEP_45_JWT_SECRET = "secret.sep45.jwt_secret";
  public static final String SECRET_SEP_45_SIMULATING_SIGNING_SEED =
      "secret.sep45.simulating_signing_seed";
  public static final String SECRET_CALLBACK_API_AUTH_SECRET = "secret.callback_api.auth_secret";
  public static final String SECRET_PLATFORM_API_AUTH_SECRET = "secret.platform_api.auth_secret";
  public static final String SECRET_DATA_USERNAME = "secret.data.username";
  public static final String SECRET_DATA_PASSWORD = "secret.data.password";
  public static final String SECRET_EVENTS_QUEUE_KAFKA_USERNAME =
      "secret.events.queue.kafka.username";
  public static final String SECRET_EVENTS_QUEUE_KAFKA_PASSWORD =
      "secret.events.queue.kafka.password";
  public static final String SECRET_SSL_KEYSTORE_PASSWORD = "secret.ssl.keystore.password";
  public static final String SECRET_SSL_KEY_PASSWORD = "secret.ssl.key.password";
  public static final String SECRET_SSL_TRUSTSTORE_PASSWORD = "secret.ssl.truststore.password";

  public String getSep6MoreInfoUrlJwtSecret() {
    return SecretManager.getInstance().get(SECRET_SEP_6_MORE_INFO_URL_JWT_SECRET);
  }

  public String getSep10JwtSecretKey() {
    return SecretManager.getInstance().get(SECRET_SEP_10_JWT_SECRET);
  }

  public String getSep45JwtSecretKey() {
    return SecretManager.getInstance().get(SECRET_SEP_45_JWT_SECRET);
  }

  public String getSep10SigningSeed() {
    return SecretManager.getInstance().get(SECRET_SEP_10_SIGNING_SEED);
  }

  public String getSep45SimulatingSigningSeed() {
    return SecretManager.getInstance().get(SECRET_SEP_45_SIMULATING_SIGNING_SEED);
  }

  @Override
  public String getSep24InteractiveUrlJwtSecret() {
    return SecretManager.getInstance().get(SECRET_SEP_24_INTERACTIVE_URL_JWT_SECRET);
  }

  @Override
  public String getSep24MoreInfoUrlJwtSecret() {
    return SecretManager.getInstance().get(SECRET_SEP_24_MORE_INFO_URL_JWT_SECRET);
  }

  @Override
  public String getCallbackAuthSecret() {
    return SecretManager.getInstance().get(SECRET_CALLBACK_API_AUTH_SECRET);
  }

  @Override
  public String getPlatformAuthSecret() {
    return SecretManager.getInstance().get(SECRET_PLATFORM_API_AUTH_SECRET);
  }

  @Override
  public String getDataSourceUsername() {
    return SecretManager.getInstance().get(SECRET_DATA_USERNAME);
  }

  @Override
  public String getDataSourcePassword() {
    return SecretManager.getInstance().get(SECRET_DATA_PASSWORD);
  }

  @Override
  public String getEventsQueueKafkaUsername() {
    return SecretManager.getInstance().get(SECRET_EVENTS_QUEUE_KAFKA_USERNAME);
  }

  @Override
  public String getEventsQueueKafkaPassword() {
    return SecretManager.getInstance().get(SECRET_EVENTS_QUEUE_KAFKA_PASSWORD);
  }
}
