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
  // The session being read by the current run, for keepAlive().
  private volatile EventService.Session activeSession = null;
  // Set when the queue refused a keep-alive, so the current run must end and be replaced.
  private volatile boolean sessionLost = false;

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
    sessionLost = false;
    infoF(
        "The EventProcessor listening task is starting for the {} time.",
        getConsumerRestartCount() + 1);
    EventService.Session queueSession = eventService.createSession(name, eventQueue);
    activeSession = queueSession;
    try {
      while (isRunning()) {
        EventService.ReadResponse readResponse = queueSession.read();
        List<AnchorEvent> events = readResponse.getEvents();
        Metrics.counter(EVENT_RECEIVED, QUEUE, toMetricTag(eventQueue.name()))
            .increment(events.size());
        debugF("Received {} events from queue", events.size());
        int handled = 0;
        for (AnchorEvent event : events) {
          if (!isRunning()) break;
          handleEventWithRetry(event);
          // An interrupt or a lost session ends the retry loops before the event is handled, so
          // the event must not be committed.
          if (currentThread().isInterrupted() || sessionLost) break;
          handled++;
          // Commit this event now so that a slow or failing event later in the batch cannot make
          // the queue deliver it again.
          queueSession.ack(readResponse, handled);
          keepAlive();
        }
        if (handled == events.size() && isRunning()) {
          // Also commits records of the batch that were skipped because they were not events.
          queueSession.ack(readResponse);
        }
      }
    } catch (Exception ex) {
      // This is unexpected, so we need to restart the consumer.
      Log.errorEx(ex);
    } finally {
      activeSession = null;
      if (sessionLost && !stopped) {
        // The loop was interrupted only to end this run. Clear the flag so the next scheduled run
        // starts clean, and so that closing the session is not interrupted.
        Thread.interrupted();
      }
      queueSession.close();
      infoF("Closing queue session [{}]", queueSession.getSessionName());
    }
  }

  /**
   * Tells the queue that the consumer is still alive. Processors call this after every wait while
   * they retry an event. If the queue refuses, the session is marked as lost and the current thread
   * is interrupted, which ends the retry loop; {@link #run()} then closes the session and the
   * scheduler starts a new one.
   */
  protected void keepAlive() {
    EventService.Session session = activeSession;
    if (session == null) return;
    try {
      session.keepAlive();
    } catch (Exception ex) {
      Log.errorEx(ex);
      sessionLost = true;
      currentThread().interrupt();
    }
  }

  private boolean isRunning() {
    return !currentThread().isInterrupted() && !stopped && !sessionLost;
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
