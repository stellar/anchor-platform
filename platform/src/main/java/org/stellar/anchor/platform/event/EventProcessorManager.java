package org.stellar.anchor.platform.event;

import static org.stellar.anchor.event.EventService.*;
import static org.stellar.anchor.util.Log.*;
import static org.stellar.anchor.util.MetricConstants.*;
import static org.stellar.anchor.util.StringHelper.json;

import java.util.ArrayList;
import java.util.List;
import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import lombok.SneakyThrows;
import org.stellar.anchor.MoreInfoUrlConstructor;
import org.stellar.anchor.api.exception.AnchorException;
import org.stellar.anchor.api.exception.InternalServerErrorException;
import org.stellar.anchor.asset.AssetService;
import org.stellar.anchor.config.SecretConfig;
import org.stellar.anchor.event.EventService;
import org.stellar.anchor.event.EventService.EventQueue;
import org.stellar.anchor.platform.config.CallbackApiConfig;
import org.stellar.anchor.platform.config.EventProcessorConfig;
import org.stellar.anchor.platform.config.PropertyClientsConfig;
import org.stellar.anchor.sep24.Sep24TransactionStore;
import org.stellar.anchor.sep31.Sep31TransactionStore;
import org.stellar.anchor.sep6.Sep6TransactionStore;
import org.stellar.anchor.util.Log;

public class EventProcessorManager {
  public static final String CLIENT_STATUS_CALLBACK_EVENT_PROCESSOR_NAME_PREFIX =
      "client-status-callback-";
  public static final String CALLBACK_API_EVENT_PROCESSOR_NAME = "callback-api";
  private final SecretConfig secretConfig;
  private final EventProcessorConfig eventProcessorConfig;
  private final CallbackApiConfig callbackApiConfig;
  private final PropertyClientsConfig clientsConfig;
  private final EventService eventService;
  private final AssetService assetService;
  private final Sep6TransactionStore sep6TransactionStore;
  private final Sep24TransactionStore sep24TransactionStore;
  private final Sep31TransactionStore sep31TransactionStore;
  private final MoreInfoUrlConstructor sep6MoreInfoUrlConstructor;
  private final MoreInfoUrlConstructor sep24MoreInfoUrlConstructor;
  private final List<EventProcessor> processors = new ArrayList<>();

  public EventProcessorManager(
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
    this.secretConfig = secretConfig;
    this.eventProcessorConfig = eventProcessorConfig;
    this.callbackApiConfig = callbackApiConfig;
    this.clientsConfig = clientsConfig;
    this.eventService = eventService;
    this.assetService = assetService;
    this.sep6TransactionStore = sep6TransactionStore;
    this.sep24TransactionStore = sep24TransactionStore;
    this.sep31TransactionStore = sep31TransactionStore;
    this.sep6MoreInfoUrlConstructor = sep6MoreInfoUrlConstructor;
    this.sep24MoreInfoUrlConstructor = sep24MoreInfoUrlConstructor;
  }

  @PostConstruct
  @SneakyThrows
  public void start() {
    if (eventProcessorConfig.getCallbackApiRequest().isEnabled()) {
      // Create a processor for the callback API handler
      processors.add(
          new CallbackApiEventProcessor(
              CALLBACK_API_EVENT_PROCESSOR_NAME,
              EventQueue.TRANSACTION,
              eventService,
              new CallbackApiEventHandler(callbackApiConfig)));
    }
    // Create a processor of the client status callback handler for each client defined in the
    // clientsConfig
    if (eventProcessorConfig.getClientStatusCallback().isEnabled()) {
      for (PropertyClientsConfig.ClientConfig clientConfig : clientsConfig.getClients()) {
        if (!clientConfig.isCallbackEnabled()) {
          Log.info(String.format("Client status callback skipped: %s", json(clientConfig)));
          continue;
        }

        String processorName;
        switch (clientConfig.getType()) {
          case CUSTODIAL:
            processorName =
                CLIENT_STATUS_CALLBACK_EVENT_PROCESSOR_NAME_PREFIX
                    + clientConfig.getSigningKeys().stream();
            break;
          case NONCUSTODIAL:
            processorName =
                CLIENT_STATUS_CALLBACK_EVENT_PROCESSOR_NAME_PREFIX
                    + clientConfig.getDomains().stream();
            break;
          default:
            errorF("Unknown client type: {}", clientConfig.getType());
            throw new InternalServerErrorException(
                "Unknown client type: " + clientConfig.getType());
        }
        processors.add(
            new ClientStatusCallbackProcessor(
                processorName,
                EventQueue.TRANSACTION,
                eventService,
                new ClientStatusCallbackHandler(
                    secretConfig,
                    clientConfig,
                    sep6TransactionStore,
                    assetService,
                    sep6MoreInfoUrlConstructor,
                    sep24MoreInfoUrlConstructor)));
      }
    }

    // Start all the processors
    processors.forEach(EventProcessor::start);
  }

  @PreDestroy
  public void stop() {
    for (EventProcessor processor : processors) {
      try {
        processor.stop();
      } catch (AnchorException ex) {
        errorF("Failed to shutdown event processor: {}}", processor.getClass());
        errorEx(ex);
      }
    }
  }
}
