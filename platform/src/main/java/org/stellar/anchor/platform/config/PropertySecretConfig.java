package org.stellar.anchor.platform.config;

import static org.stellar.anchor.util.KeyUtil.toSecretKeySpecOrNull;

import javax.crypto.SecretKey;
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
  public static final String SECRET_CALLBACK_API_AUTH_SECRET = "secret.callback_api.auth_secret";
  public static final String SECRET_PLATFORM_API_AUTH_SECRET = "secret.platform_api.auth_secret";
  public static final String SECRET_DATA_USERNAME = "secret.data.username";
  public static final String SECRET_DATA_PASSWORD = "secret.data.password";
  public static final String SECRET_EVENTS_QUEUE_KAFKA_USERNAME =
      "secret.events.queue.kafka.username";
  public static final String SECRET_EVENTS_QUEUE_KAFKA_PASSWORD =
      "secret.events.queue.kafka.password";

  public String getSep6MoreInfoUrlJwtSecret() {
    return SecretManager.getInstance().get(SECRET_SEP_6_MORE_INFO_URL_JWT_SECRET);
  }

  public SecretKey getSep10JwtSecretKey() {
    return toSecretKeySpecOrNull(SecretManager.getInstance().get(SECRET_SEP_10_JWT_SECRET));
  }

  public String getSep10SigningSeed() {
    return SecretManager.getInstance().get(SECRET_SEP_10_SIGNING_SEED);
  }

  @Override
  public SecretKey getSep24InteractiveUrlJwtSecret() {
    return toSecretKeySpecOrNull(
        SecretManager.getInstance().get(SECRET_SEP_24_INTERACTIVE_URL_JWT_SECRET));
  }

  @Override
  public SecretKey getSep24MoreInfoUrlJwtSecret() {
    return toSecretKeySpecOrNull(
        SecretManager.getInstance().get(SECRET_SEP_24_MORE_INFO_URL_JWT_SECRET));
  }

  @Override
  public String getCallbackAuthSecret() {
    return SecretManager.getInstance().get(SECRET_CALLBACK_API_AUTH_SECRET);
  }

  @Override
  public SecretKey getCallbackAuthSecretKey() {
    return toSecretKeySpecOrNull(getCallbackAuthSecret());
  }

  @Override
  public String getPlatformAuthSecret() {
    return SecretManager.getInstance().get(SECRET_PLATFORM_API_AUTH_SECRET);
  }

  @Override
  public SecretKey getPlatformAuthSecretKey() {
    return toSecretKeySpecOrNull(getPlatformAuthSecret());
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
