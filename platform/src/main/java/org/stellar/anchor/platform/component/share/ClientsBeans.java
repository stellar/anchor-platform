package org.stellar.anchor.platform.component.share;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.stellar.anchor.api.exception.InvalidConfigException;
import org.stellar.anchor.client.ClientService;
import org.stellar.anchor.client.DefaultClientService;
import org.stellar.anchor.config.ClientsConfig;
import org.stellar.anchor.platform.config.PropertyClientsConfig;
import org.stellar.anchor.platform.config.PropertyClientsConfig_DEPRECATED;

@Configuration
public class ClientsBeans {
  @Bean
  @ConfigurationProperties(prefix = "")
  PropertyClientsConfig_DEPRECATED clientsConfigDeprecated() {
    return new PropertyClientsConfig_DEPRECATED();
  }

  @Bean
  @ConfigurationProperties(prefix = "clients")
  ClientsConfig clientsConfig() {
    return new PropertyClientsConfig();
  }

  @Bean
  ClientService clientService(ClientsConfig clientsConfig) throws InvalidConfigException {
    return DefaultClientService.fromClientsConfig(clientsConfig);
  }
}
