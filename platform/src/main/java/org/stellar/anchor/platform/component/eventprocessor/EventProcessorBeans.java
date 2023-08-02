package org.stellar.anchor.platform.component.eventprocessor;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.stellar.anchor.asset.AssetService;
import org.stellar.anchor.event.EventService;
import org.stellar.anchor.platform.config.CallbackApiConfig;
import org.stellar.anchor.platform.config.ClientsConfig;
import org.stellar.anchor.platform.config.EventProcessorConfig;
import org.stellar.anchor.platform.event.EventProcessorManager;
import org.stellar.anchor.sep24.MoreInfoUrlConstructor;
import org.stellar.anchor.sep24.Sep24TransactionStore;

@Configuration
public class EventProcessorBeans {

  @Bean
  EventProcessorManager eventProcessorManager(
      EventProcessorConfig eventProcessorConfig,
      CallbackApiConfig callbackApiConfig,
      ClientsConfig clientsConfig,
      EventService eventService,
      AssetService assetService,
      Sep24TransactionStore sep24TransactionStore,
      MoreInfoUrlConstructor moreInfoUrlConstructor) {
    return new EventProcessorManager(
        eventProcessorConfig,
        callbackApiConfig,
        clientsConfig,
        eventService,
        assetService,
        sep24TransactionStore,
        moreInfoUrlConstructor);
  }
}
