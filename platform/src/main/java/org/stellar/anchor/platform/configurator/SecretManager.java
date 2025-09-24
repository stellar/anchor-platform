package org.stellar.anchor.platform.configurator;

import static org.stellar.anchor.util.Log.info;
import static org.stellar.anchor.util.StringHelper.isNotEmpty;

import java.util.Arrays;
import java.util.List;
import java.util.Properties;
import org.jetbrains.annotations.NotNull;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.PropertiesPropertySource;
import org.stellar.anchor.platform.config.PropertyCustodySecretConfig;
import org.stellar.anchor.platform.config.PropertySecretConfig;

public class SecretManager
    implements ApplicationContextInitializer<ConfigurableApplicationContext> {
  final List<String> secretVars =
      Arrays.asList(
          PropertySecretConfig.SECRET_SEP_6_MORE_INFO_URL_JWT_SECRET,
          PropertySecretConfig.SECRET_SEP_10_JWT_SECRET,
          PropertySecretConfig.SECRET_SEP_10_SIGNING_SEED,
          PropertySecretConfig.SECRET_SEP_24_INTERACTIVE_URL_JWT_SECRET,
          PropertySecretConfig.SECRET_SEP_24_MORE_INFO_URL_JWT_SECRET,
          PropertySecretConfig.SECRET_CALLBACK_API_AUTH_SECRET,
          PropertySecretConfig.SECRET_PLATFORM_API_AUTH_SECRET,
          PropertyCustodySecretConfig.SECRET_CUSTODY_SERVER_AUTH_SECRET,
          PropertySecretConfig.SECRET_DATA_USERNAME,
          PropertySecretConfig.SECRET_DATA_PASSWORD,
          PropertySecretConfig.SECRET_EVENTS_QUEUE_KAFKA_USERNAME,
          PropertySecretConfig.SECRET_EVENTS_QUEUE_KAFKA_PASSWORD,
          PropertyCustodySecretConfig.SECRET_FIREBLOCKS_SECRET_KEY,
          PropertyCustodySecretConfig.SECRET_FIREBLOCKS_API_KEY,
          PropertySecretConfig.SECRET_SSL_KEYSTORE_PASSWORD,
          PropertySecretConfig.SECRET_SSL_KEY_PASSWORD,
          PropertySecretConfig.SECRET_SSL_TRUSTSTORE_PASSWORD);

  final Properties props = new Properties();

  static final SecretManager secretManager = new SecretManager();

  private SecretManager() {}

  public static SecretManager getInstance() {
    return secretManager;
  }

  public static String secret(String key) {
    return getInstance().get(key);
  }

  @Override
  public void initialize(@NotNull ConfigurableApplicationContext applicationContext) {
    info("Secret manager started.");
    secretVars.forEach(
        var -> {
          String secret = ConfigEnvironment.getenv(var);
          if (isNotEmpty(secret)) {
            props.put(var, secret);
          }
        });
    // Set Platform configurations
    PropertiesPropertySource pps = new PropertiesPropertySource("secret", props);
    applicationContext.getEnvironment().getPropertySources().addFirst(pps);
  }

  public String get(String key) {
    return props.getProperty(key);
  }
}
