package org.stellar.anchor.platform.event;

import static org.stellar.anchor.util.Log.*;

import java.io.IOException;
import org.stellar.anchor.api.callback.SendEventRequest;
import org.stellar.anchor.api.callback.SendEventResponse;
import org.stellar.anchor.api.event.AnchorEvent;
import org.stellar.anchor.api.exception.InvalidConfigException;
import org.stellar.anchor.apiclient.CallbackApiClient;
import org.stellar.anchor.platform.config.CallbackApiConfig;

public class CallbackApiEventHandler extends EventHandler {
  final CallbackApiClient callbackApiClient;

  CallbackApiEventHandler(CallbackApiConfig callbackApiConfig) throws InvalidConfigException {
    callbackApiClient =
        new CallbackApiClient(callbackApiConfig.buildAuthHelper(), callbackApiConfig.getBaseUrl());
  }

  @Override
  boolean handleEvent(AnchorEvent event) throws IOException {
    debugF("Sending event {} to callback API.", event.getId());
    traceF("Sending event to callback API: {}", event);

    try {
      SendEventResponse sendEventResponse =
          callbackApiClient.sendEvent(SendEventRequest.from(event));
      debugF(
          "Event {} sent to callback API. code: {}, message: {}",
          event.getId(),
          sendEventResponse.getCode(),
          sendEventResponse.getMessage());
      return true;
    } catch (Exception e) {
      errorEx("Failed to send event to callback API. Error code: {}", e);
      return false;
    }
  }
}
