package org.stellar.anchor.platform.config;

import static org.stellar.anchor.util.StringHelper.isEmpty;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jetbrains.annotations.NotNull;
import org.springframework.validation.Errors;
import org.springframework.validation.Validator;
import org.stellar.anchor.config.SecretConfig;
import org.stellar.anchor.platform.configurator.SecretManager;
import org.stellar.anchor.util.KeyUtil;

public class PropertySecretConfig implements SecretConfig, Validator {

  public static final String SECRET_SEP_6_MORE_INFO_URL_JWT_SECRET =
      "secret.sep6.more_info_url.jwt_secret";
  public static final String SECRET_SEP_10_JWT_SECRET = "secret.sep10.jwt_secret";
  public static final String SECRET_SEP_10_SIGNING_SEED = "secret.sep10.signing_seed";
  public static final String SECRET_SEP_24_INTERACTIVE_URL_JWT_SECRET =
      "secret.sep24.interactive_url.jwt_secret";
  public static final String SECRET_SEP_24_MORE_INFO_URL_JWT_SECRET =
      "secret.sep24.more_info_url.jwt_secret";
  public static final String SECRET_SEP_45_JWT_SECRET = "secret.sep45.jwt_secret";
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
  public static final String SECRET_RPC_AUTH_SECRET = "secret.stellar_network.rpc.auth.secret";

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

  @Override
  public String getRpcAuthSecret() {
    return SecretManager.getInstance().get(SECRET_RPC_AUTH_SECRET);
  }

  @Override
  public boolean supports(@NotNull Class<?> clazz) {
    return PropertySecretConfig.class.isAssignableFrom(clazz);
  }

  @Override
  public void validate(@NotNull Object target, @NotNull Errors errors) {
    PropertySecretConfig config = (PropertySecretConfig) target;

    if (!isEmpty(config.getSep45JwtSecretKey())) {
      KeyUtil.rejectWeakJWTSecret(config.getSep45JwtSecretKey(), errors, "secret.sep45.jwt_secret");
    }

    Map<String, String> jwtSecrets = new LinkedHashMap<>();
    addSecretIfPresent(jwtSecrets, SECRET_PLATFORM_API_AUTH_SECRET, config.getPlatformAuthSecret());
    addSecretIfPresent(jwtSecrets, SECRET_CALLBACK_API_AUTH_SECRET, config.getCallbackAuthSecret());
    addSecretIfPresent(jwtSecrets, SECRET_SEP_10_JWT_SECRET, config.getSep10JwtSecretKey());
    addSecretIfPresent(jwtSecrets, SECRET_SEP_45_JWT_SECRET, config.getSep45JwtSecretKey());
    addSecretIfPresent(
        jwtSecrets,
        SECRET_SEP_24_INTERACTIVE_URL_JWT_SECRET,
        config.getSep24InteractiveUrlJwtSecret());
    addSecretIfPresent(
        jwtSecrets, SECRET_SEP_24_MORE_INFO_URL_JWT_SECRET, config.getSep24MoreInfoUrlJwtSecret());
    addSecretIfPresent(
        jwtSecrets, SECRET_SEP_6_MORE_INFO_URL_JWT_SECRET, config.getSep6MoreInfoUrlJwtSecret());

    List<String> keys = new ArrayList<>(jwtSecrets.keySet());
    for (int i = 0; i < keys.size(); i++) {
      for (int j = i + 1; j < keys.size(); j++) {
        String keyA = keys.get(i);
        String keyB = keys.get(j);

        if (jwtSecrets.get(keyA).equals(jwtSecrets.get(keyB))) {
          errors.reject(
              "secrets.jwt.must_be_unique",
              String.format(
                  "JWT secrets must be distinct: [%s] and [%s] share the same value."
                      + " Colliding secrets collapse the token type boundary and allow"
                      + " token type confusion attacks.",
                  keyA, keyB));
        }
      }
    }
  }

  private static void addSecretIfPresent(Map<String, String> map, String key, String value) {
    if (!isEmpty(value)) {
      map.put(key, value);
    }
  }
}
