package org.stellar.anchor.platform.component.eventprocessor;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.stellar.anchor.MoreInfoUrlConstructor;
import org.stellar.anchor.asset.AssetService;
import org.stellar.anchor.config.SecretConfig;
import org.stellar.anchor.event.EventService;
import org.stellar.anchor.platform.component.sep.ApiClientBeans;
import org.stellar.anchor.platform.config.CallbackApiConfig;
import org.stellar.anchor.platform.config.EventProcessorConfig;
import org.stellar.anchor.platform.config.PropertyClientsConfig;
import org.stellar.anchor.platform.event.EventProcessorManager;
import org.stellar.anchor.sep24.Sep24TransactionStore;
import org.stellar.anchor.sep31.Sep31TransactionStore;
import org.stellar.anchor.sep6.Sep6TransactionStore;

@Configuration
@Import(ApiClientBeans.class)
public class EventProcessorBeans {

  @Bean
  EventProcessorManager eventProcessorManager(
      SecretConfig secretConfig,
      EventProcessorConfig eventProcessorConfig,
      CallbackApiConfig callbackApiConfig,
      PropertyClientsConfig clientsConfig,
      EventService eventService,
      AssetService assetService,
      Sep6TransactionStore sep6TransactionStore,
      Sep24TransactionStore sep24TransactionStore,
      Sep31TransactionStore sep31TransactionStore,
      MoreInfoUrlConstructor sep6MoreInfoUrlConstructor,
      MoreInfoUrlConstructor sep24MoreInfoUrlConstructor) {
    return new EventProcessorManager(
        secretConfig,
        eventProcessorConfig,
        callbackApiConfig,
        clientsConfig,
        eventService,
        assetService,
        sep6TransactionStore,
        sep24TransactionStore,
        sep31TransactionStore,
        sep6MoreInfoUrlConstructor,
        sep24MoreInfoUrlConstructor);
  }
}
