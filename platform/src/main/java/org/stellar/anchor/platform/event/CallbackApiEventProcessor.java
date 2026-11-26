package org.stellar.anchor.platform.event;

import static java.lang.Thread.currentThread;
import static org.stellar.anchor.util.MetricConstants.TV_BUSINESS_SERVER_CALLBACK;

import java.io.IOException;
import org.stellar.anchor.api.event.AnchorEvent;
import org.stellar.anchor.event.EventService;
import org.stellar.anchor.util.ExponentialBackoffTimer;
import org.stellar.anchor.util.Log;

public class CallbackApiEventProcessor extends EventProcessor {
  private final CallbackApiEventHandler eventHandler;
  // The longest wait between two attempts. The consumer is kept alive after every wait, so this
  // bounds the time between two polls while an event is being retried.
  static final long MAX_BACKOFF_SECONDS = 30;
  private final ExponentialBackoffTimer backoffTimer =
      new ExponentialBackoffTimer(1, MAX_BACKOFF_SECONDS);

  public CallbackApiEventProcessor(
      String name,
      EventService.EventQueue eventQueue,
      EventService eventService,
      CallbackApiEventHandler eventHandler) {
    super(name, eventQueue, eventService);
    this.eventHandler = eventHandler;
  }

  @Override
  void handleEventWithRetry(AnchorEvent event) {
    boolean isProcessed = false;
    // For every event, reset the timer.
    getBackoffTimer().reset();
    // Infinite retry until the event is processed or the thread is interrupted.
    while (!isProcessed && !currentThread().isInterrupted()) {
      try {
        if (eventHandler.handleEvent(event)) {
          isProcessed = true;
          incrementProcessedCounter();
        } else {
          try {
            getBackoffTimer().backoff();
            keepAlive();
          } catch (InterruptedException e) {
            currentThread().interrupt();
          }
        }
      } catch (IOException ioex) {
        Log.errorEx(ioex);
        try {
          getBackoffTimer().backoff();
          keepAlive();
        } catch (InterruptedException e) {
          currentThread().interrupt();
        }
      }
    }
  }

  @Override
  String toMetricTag(String queueName) {
    return TV_BUSINESS_SERVER_CALLBACK;
  }

  ExponentialBackoffTimer getBackoffTimer() {
    return backoffTimer;
  }
}
