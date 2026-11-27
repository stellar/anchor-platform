package org.stellar.anchor.platform.configurator;

import static org.stellar.anchor.util.Log.error;
import static org.stellar.anchor.util.Log.info;
import static org.stellar.anchor.util.StringHelper.isNotEmpty;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import org.jetbrains.annotations.NotNull;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.PropertiesPropertySource;
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
          PropertySecretConfig.SECRET_SEP_45_JWT_SECRET,
          PropertySecretConfig.SECRET_CALLBACK_API_AUTH_SECRET,
          PropertySecretConfig.SECRET_PLATFORM_API_AUTH_SECRET,
          PropertySecretConfig.SECRET_DATA_USERNAME,
          PropertySecretConfig.SECRET_DATA_PASSWORD,
          PropertySecretConfig.SECRET_EVENTS_QUEUE_KAFKA_USERNAME,
          PropertySecretConfig.SECRET_EVENTS_QUEUE_KAFKA_PASSWORD,
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
    Map<String, String> secrets = new LinkedHashMap<>();
    secretVars.forEach(
        var -> {
          String secret = ConfigEnvironment.getenv(var);
          if (isNotEmpty(secret)) {
            rejectLostCharacters(var, secret);
            secrets.put(var, secret);
          }
        });
    props.putAll(secrets);
    // Set Platform configurations
    PropertiesPropertySource pps = new PropertiesPropertySource("secret", props);
    applicationContext.getEnvironment().getPropertySources().addFirst(pps);
  }

  /**
   * The JVM decodes environment values with the locale's charset. In a locale that is not UTF-8 it
   * replaces every byte it cannot decode with U+FFFD, so a non-ASCII secret reaches the platform as
   * a run of replacement characters that an attacker can guess. Refuse to start instead. The
   * message never repeats the value or its length.
   */
  private static void rejectLostCharacters(String var, String secret) {
    if (secret.indexOf('\uFFFD') < 0) {
      return;
    }
    String message =
        String.format(
            "%s lost characters when the JVM read it from the environment: it contains the"
                + " replacement character U+FFFD, which the JVM writes for bytes it cannot decode"
                + " in a locale that is not UTF-8. Run the JVM in a UTF-8 locale (LANG=C.UTF-8, as"
                + " the official image does), or pass -Dfile.encoding=UTF-8 through JVM_FLAGS, or"
                + " use a secret with ASCII characters only.",
            var);
    error(message);
    throw new IllegalStateException(message);
  }

  public String get(String key) {
    return props.getProperty(key);
  }
}
