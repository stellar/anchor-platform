package org.stellar.anchor.platform.component.share;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.stellar.anchor.config.SecretConfig;
import org.stellar.anchor.platform.config.AppLoggingConfig;
import org.stellar.anchor.platform.config.CallbackApiConfig;
import org.stellar.anchor.platform.config.PlatformApiConfig;
import org.stellar.anchor.platform.config.PlatformServerConfig;
import org.stellar.anchor.platform.config.PropertyDataConfig;
import org.stellar.anchor.platform.config.PropertySecretConfig;

@Configuration
public class SharedConfigBeans {

  @Bean
  @ConfigurationProperties(prefix = "data")
  PropertyDataConfig dataConfig(SecretConfig secretConfig) {
    return new PropertyDataConfig(secretConfig);
  }

  @Bean
  @ConfigurationProperties(prefix = "platform-api")
  PlatformApiConfig platformApiConfig(PropertySecretConfig secretConfig) {
    return new PlatformApiConfig(secretConfig);
  }

  @Bean
  @ConfigurationProperties(prefix = "callback-api")
  CallbackApiConfig callbackApiConfig(PropertySecretConfig secretConfig) {
    return new CallbackApiConfig(secretConfig);
  }

  @Bean
  @ConfigurationProperties(prefix = "platform-server")
  PlatformServerConfig platformServerConfig(PropertySecretConfig secretConfig) {
    return new PlatformServerConfig(secretConfig);
  }

  @Bean
  @ConfigurationProperties(prefix = "app-logging")
  AppLoggingConfig appLoggingConfig() {
    return new AppLoggingConfig();
  }
}
