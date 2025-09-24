package org.stellar.anchor.platform.event;

import static java.lang.Thread.currentThread;
import static org.stellar.anchor.util.Log.debugF;
import static org.stellar.anchor.util.Log.infoF;
import static org.stellar.anchor.util.MetricConstants.*;

import io.micrometer.core.instrument.Metrics;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import lombok.SneakyThrows;
import org.stellar.anchor.api.event.AnchorEvent;
import org.stellar.anchor.api.exception.AnchorException;
import org.stellar.anchor.event.EventService;
import org.stellar.anchor.event.EventService.EventQueue;
import org.stellar.anchor.platform.utils.DaemonExecutors;
import org.stellar.anchor.util.Log;

public abstract class EventProcessor implements Runnable {
  private final String name;
  private final EventQueue eventQueue;
  private final EventService eventService;
  private final ScheduledExecutorService consumerScheduler =
      DaemonExecutors.newScheduledThreadPool(1);
  private ScheduledFuture<?> processingTask = null;
  // The flag to indicate if the processor is stopped.
  private boolean stopped = false;

  protected EventProcessor(
      String name, EventService.EventQueue eventQueue, EventService eventService) {
    this.name = name;
    this.eventQueue = eventQueue;
    this.eventService = eventService;
  }

  public void start() {
    processingTask = consumerScheduler.scheduleWithFixedDelay(this, 1, 2, TimeUnit.SECONDS);
  }

  public void stop() throws AnchorException {
    stopped = true;
    if (processingTask != null) {
      processingTask.cancel(true);
    }
    consumerScheduler.shutdown();
  }

  @SneakyThrows
  @Override
  public void run() {
    infoF(
        "The EventProcessor listening task is starting for the {} time.",
        getConsumerRestartCount() + 1);
    EventService.Session queueSession = eventService.createSession(name, eventQueue);
    try {
      while (!currentThread().isInterrupted() && !stopped) {
        EventService.ReadResponse readResponse = queueSession.read();
        List<AnchorEvent> events = readResponse.getEvents();
        Metrics.counter(EVENT_RECEIVED, QUEUE, toMetricTag(eventQueue.name()))
            .increment(events.size());
        debugF("Received {} events from queue", events.size());
        for (AnchorEvent event : events) {
          handleEventWithRetry(event);
          if (currentThread().isInterrupted()) break;
        }
        queueSession.ack(readResponse);
      }

      queueSession.close();
    } catch (Exception ex) {
      // This is unexpected, so we need to restart the consumer.
      Log.errorEx(ex);
    } finally {
      queueSession.close();
      infoF("Closing queue session [{}]", queueSession.getSessionName());
    }
  }

  abstract void handleEventWithRetry(AnchorEvent event);

  abstract String toMetricTag(String queueName);

  long getConsumerRestartCount() {
    return ((ScheduledThreadPoolExecutor) consumerScheduler).getCompletedTaskCount();
  }

  void incrementProcessedCounter() {
    Metrics.counter(EVENT_PROCESSED, QUEUE, toMetricTag(eventQueue.name())).increment();
  }
}
