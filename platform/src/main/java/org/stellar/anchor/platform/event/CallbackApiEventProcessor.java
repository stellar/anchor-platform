package org.stellar.anchor.platform.event;

import static java.lang.Thread.currentThread;
import static org.stellar.anchor.util.MetricConstants.EVENT_DROPPED;
import static org.stellar.anchor.util.MetricConstants.QUEUE;
import static org.stellar.anchor.util.MetricConstants.TV_BUSINESS_SERVER_CALLBACK;

import io.micrometer.core.instrument.Metrics;
import java.io.IOException;
import java.util.Set;
import org.stellar.anchor.api.event.AnchorEvent;
import org.stellar.anchor.api.exception.HttpException;
import org.stellar.anchor.event.EventService;
import org.stellar.anchor.util.ExponentialBackoffTimer;
import org.stellar.anchor.util.Log;

public class CallbackApiEventProcessor extends EventProcessor {
  static final int MAX_REJECTED_ATTEMPTS = 3;
  private static final Set<Integer> RETRYABLE_CLIENT_ERRORS = Set.of(401, 403, 404, 408, 429);

  private final CallbackApiEventHandler eventHandler;
  private final ExponentialBackoffTimer backoffTimer = new ExponentialBackoffTimer();

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
    int rejectedAttempts = 0;
    getBackoffTimer().reset();
    while (!isProcessed && !currentThread().isInterrupted()) {
      try {
        if (eventHandler.handleEvent(event)) {
          isProcessed = true;
          incrementProcessedCounter();
        } else {
          backoff();
        }
      } catch (HttpException hex) {
        if (isEventRejected(hex.getStatusCode()) && ++rejectedAttempts >= MAX_REJECTED_ATTEMPTS) {
          dropEvent(event, hex.getStatusCode());
          isProcessed = true;
        } else {
          backoff();
        }
      } catch (IOException ioex) {
        Log.errorEx(ioex);
        backoff();
      }
    }
  }

  static boolean isEventRejected(int statusCode) {
    return statusCode >= 400 && statusCode < 500 && !RETRYABLE_CLIENT_ERRORS.contains(statusCode);
  }

  void dropEvent(AnchorEvent event, int statusCode) {
    Log.errorF(
        "Dropping event {} after the callback API rejected it {} times. status={}, type={}, sep={}, txnId={}",
        event.getId(),
        MAX_REJECTED_ATTEMPTS,
        statusCode,
        event.getType(),
        event.getSep(),
        event.getTransaction() == null ? null : event.getTransaction().getId());
    Metrics.counter(EVENT_DROPPED, QUEUE, TV_BUSINESS_SERVER_CALLBACK).increment();
  }

  private void backoff() {
    try {
      getBackoffTimer().backoff();
    } catch (InterruptedException e) {
      currentThread().interrupt();
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
