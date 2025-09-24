package org.stellar.anchor.platform.event;

import static java.lang.Thread.currentThread;
import static org.stellar.anchor.util.MetricConstants.*;
import static org.stellar.anchor.util.StringHelper.json;

import java.io.IOException;
import org.stellar.anchor.api.event.AnchorEvent;
import org.stellar.anchor.event.EventService;
import org.stellar.anchor.util.ExponentialBackoffTimer;
import org.stellar.anchor.util.Log;

public class ClientStatusCallbackProcessor extends EventProcessor {
  private final ClientStatusCallbackHandler eventHandler;
  // The initial backoff time for connection error.
  private final long NETWORK_INITIAL_BACKOFF_TIME_SECONDS = 1;
  // The maximum backoff time for connection error.
  private final long NETWORK_MAX_BACKOFF_TIME_SECONDS = 30;
  // The initial backoff time for HTTP status code other than 200s or 300s.
  private final long HTTP_STATUS_INITIAL_BACKOFF_TIME_SECONDS = 1;
  // The maximum backoff time for HTTP status code other than 200s or 300s.
  private final long HTTP_STATUS_MAX_BACKOFF_TIME_SECONDS = 5;
  // The maximum number of retries for HTTP status code other than 200s or 300s.
  private final long MAX_RETRIES = 3;
  private final ExponentialBackoffTimer networkBackoffTimer =
      new ExponentialBackoffTimer(
          NETWORK_INITIAL_BACKOFF_TIME_SECONDS, NETWORK_MAX_BACKOFF_TIME_SECONDS);
  private final ExponentialBackoffTimer httpErrorBackoffTimer =
      new ExponentialBackoffTimer(
          HTTP_STATUS_INITIAL_BACKOFF_TIME_SECONDS, HTTP_STATUS_MAX_BACKOFF_TIME_SECONDS);

  protected ClientStatusCallbackProcessor(
      String name,
      EventService.EventQueue eventQueue,
      EventService eventService,
      ClientStatusCallbackHandler eventHandler) {
    super(name, eventQueue, eventService);
    this.eventHandler = eventHandler;
  }

  @Override
  void handleEventWithRetry(AnchorEvent event) {
    boolean isProcessed = false;
    int retryAttempts = 0;
    // For every event, reset the timer.
    getHttpErrorBackoffTimer().reset();
    getNetworkBackoffTimer().reset();
    // Retry until the event is processed or the thread is interrupted.
    while (!isProcessed && !currentThread().isInterrupted()) {
      try {
        if (eventHandler.handleEvent(event)) {
          // ***** The event is processed successfully.
          isProcessed = true;
          incrementProcessedCounter();
        } else {
          // ***** Error #2. HTTP status code other than 200s or 300s
          if (++retryAttempts < MAX_RETRIES) {
            // retry.
            try {
              getHttpErrorBackoffTimer().backoff();
            } catch (InterruptedException e) {
              // The thread is interrupted, so we need to stop the processor. This will
              // break the while loop.
              currentThread().interrupt();
            }
          } else {
            // retry >= 3 times, skip the event.
            isProcessed = true;
            incrementProcessedCounter();
          }
        }
      } catch (IOException ioex) {
        // Retry for connection error
        if (++retryAttempts < MAX_RETRIES) {
          try {
            getNetworkBackoffTimer().backoff();
          } catch (InterruptedException e) {
            // The thread is interrupted, so we need to stop the processor. This will
            // break the while loop.
            currentThread().interrupt();
          }
        } else {
          isProcessed = true;
          incrementProcessedCounter();
        }
      } catch (Exception e) {
        // ***** Error #3. uncaught exception
        sendToDLQ(event, e);
        isProcessed = true;
      }
    }
  }

  @Override
  String toMetricTag(String queueName) {
    return TV_STATUS_CALLBACK;
  }

  ExponentialBackoffTimer getHttpErrorBackoffTimer() {
    return httpErrorBackoffTimer;
  }

  ExponentialBackoffTimer getNetworkBackoffTimer() {
    return networkBackoffTimer;
  }

  void sendToDLQ(AnchorEvent event, Exception e) {
    Log.errorF("Failed to process event: {}", json(event));
    Log.errorEx(e);
  }
}
