package org.stellar.anchor.platform.observer.stellar;

import static io.micrometer.core.instrument.Metrics.gauge;
import static org.stellar.anchor.platform.observer.stellar.AbstractPaymentObserver.ObserverStatus.*;
import static org.stellar.anchor.util.Log.*;
import static org.stellar.anchor.util.Log.debugF;
import static org.stellar.anchor.util.MetricConstants.PAYMENT_OBSERVER_LATEST_BLOCK_PROCESSED;
import static org.stellar.anchor.util.MetricConstants.PAYMENT_OBSERVER_LATEST_BLOCK_READ;

import com.google.gson.annotations.SerializedName;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import lombok.Builder;
import lombok.Data;
import org.jetbrains.annotations.NotNull;
import org.springframework.transaction.TransactionException;
import org.stellar.anchor.api.exception.AnchorException;
import org.stellar.anchor.api.platform.HealthCheckResult;
import org.stellar.anchor.api.platform.HealthCheckStatus;
import org.stellar.anchor.healthcheck.HealthCheckable;
import org.stellar.anchor.ledger.PaymentTransferEvent;
import org.stellar.anchor.platform.config.PaymentObserverConfig.StellarPaymentObserverConfig;
import org.stellar.anchor.platform.observer.PaymentListener;
import org.stellar.anchor.platform.utils.DaemonExecutors;
import org.stellar.anchor.util.ExponentialBackoffTimer;

public abstract class AbstractPaymentObserver implements HealthCheckable {
  final StellarPaymentObserverConfig config;
  final List<PaymentListener> paymentListeners;
  final StellarPaymentStreamerCursorStore paymentStreamerCursorStore;
  final PaymentObservingAccountsManager paymentObservingAccountsManager;

  final ExponentialBackoffTimer publishingBackoffTimer;
  final ExponentialBackoffTimer streamBackoffTimer;
  final ExponentialBackoffTimer databaseBackoffTimer = new ExponentialBackoffTimer(1, 20);

  int silenceTimeoutCount = 0;
  HorizonPaymentObserver.ObserverStatus status = RUNNING;
  Instant lastActivityTime;
  AtomicLong metricLatestBlockRead = new AtomicLong(0);
  AtomicLong metricLatestBlockProcessed = new AtomicLong(0);

  final ScheduledExecutorService silenceWatcher = DaemonExecutors.newScheduledThreadPool(1);
  final ScheduledExecutorService statusWatcher = DaemonExecutors.newScheduledThreadPool(1);

  AbstractPaymentObserver(
      StellarPaymentObserverConfig config,
      List<PaymentListener> paymentListeners,
      PaymentObservingAccountsManager paymentObservingAccountsManager,
      StellarPaymentStreamerCursorStore paymentStreamerCursorStore) {
    this.config = config;
    this.paymentListeners = paymentListeners;
    this.paymentStreamerCursorStore = paymentStreamerCursorStore;
    this.paymentObservingAccountsManager = paymentObservingAccountsManager;

    publishingBackoffTimer =
        new ExponentialBackoffTimer(
            config.getInitialEventBackoffTime(), config.getMaxEventBackoffTime());
    streamBackoffTimer =
        new ExponentialBackoffTimer(
            config.getInitialStreamBackoffTime(), config.getMaxStreamBackoffTime());

    // latest block read
    gauge(PAYMENT_OBSERVER_LATEST_BLOCK_READ, metricLatestBlockRead);
    // latest block processed
    gauge(PAYMENT_OBSERVER_LATEST_BLOCK_PROCESSED, metricLatestBlockProcessed);
  }

  abstract void startInternal();

  abstract void shutdownInternal();

  /** Start the payment observer. */
  public void start() {
    infoF("Starting the payment observer");
    startInternal();

    infoF("Starting the observer silence watcher");
    silenceWatcher.scheduleAtFixedRate(
        this::checkSilence, 1, config.getSilenceCheckInterval(), TimeUnit.SECONDS);

    infoF("Starting the status watcher");
    statusWatcher.scheduleWithFixedDelay(this::checkStatus, 1, 1, TimeUnit.SECONDS);

    setStatus(RUNNING);
  }

  /** Gracefully shutdown the payment observer. */
  public void shutdown() {
    infoF("Shutting down the payment observer");
    shutdownInternal();

    infoF("Stopping the silence watcher");
    silenceWatcher.shutdown();

    infoF("Stopping the status watcher");
    statusWatcher.shutdown();
    setStatus(SHUTDOWN);
  }

  /**
   * Restart the payment observer. This is used when the payment observer is in error state and
   * needs to be restarted.
   *
   * <p>The timers are not reset.
   */
  void restartInternal() {
    try {
      infoF("Restarting the payment observer");
      shutdownInternal();
      startInternal();
      setStatus(RUNNING);
    } catch (TransactionException tex) {
      errorEx("Error restarting stream.", tex);
      setStatus(DATABASE_ERROR);
    }
  }

  /**
   * Handle the payment transfer event. This is called when a new payment transfer event is received
   * from the ledger.
   *
   * @param transferEvent the payment transfer event
   * @throws AnchorException if the event cannot be handled
   * @throws IOException if there is an error processing the event
   */
  void handleEvent(PaymentTransferEvent transferEvent) throws AnchorException, IOException {
    metricLatestBlockRead.set(transferEvent.getLedgerTransaction().getLedger());
    // process the payment
    for (PaymentListener listener : paymentListeners) {
      listener.onReceived(transferEvent);
    }
    metricLatestBlockProcessed.set(transferEvent.getLedgerTransaction().getLedger());
    publishingBackoffTimer.reset();
  }

  @Override
  public int compareTo(@NotNull HealthCheckable other) {
    return this.getName().compareTo(other.getName());
  }

  void setStatus(HorizonPaymentObserver.ObserverStatus status) {
    if (this.status != status) {
      if (this.status.isSettable(status)) {
        infoF("Setting status to {}", status);
        this.status = status;
      } else {
        warnF("Cannot set status to {} while the current status is {}", status, this.status);
      }
    }
  }

  void checkSilence() {
    if (isHealthy()) {
      Instant now = Instant.now();
      if (lastActivityTime != null) {
        Duration silenceDuration = Duration.between(lastActivityTime, now);
        if (silenceDuration.getSeconds() > config.getSilenceTimeout()) {
          debugF(
              "The observer had been silent for {} seconds. This is too long. Setting status to SILENCE_ERROR",
              silenceDuration.getSeconds());
          setStatus(SILENCE_ERROR);
        } else {
          debugF("The observer had been silent for {} seconds.", silenceDuration.getSeconds());
        }
      }
    }
  }

  void checkStatus() {
    switch (status) {
      case NEEDS_SHUTDOWN:
        infoF("shut down the observer");
        shutdown();
        break;
      case STREAM_ERROR:
        // We got stream connection error. We will use the backoff timer to reconnect.
        // If the backoff timer reaches max, we will shut down the observer
        if (streamBackoffTimer.isTimerMaxed()) {
          infoF("The streamer backoff timer is maxed. Shutdown the observer");
          setStatus(NEEDS_SHUTDOWN);
        } else {
          try {
            infoF(
                "The streamer needs restart. Start backoff timer: {} seconds",
                streamBackoffTimer.currentTimer());
            streamBackoffTimer.backoff();
            restartInternal();
          } catch (InterruptedException e) {
            // if this thread is interrupted, we are shutting down the status watcher.
            infoF("The status watcher is interrupted. Shutdown the observer");
            setStatus(NEEDS_SHUTDOWN);
          }
        }
        break;
      case SILENCE_ERROR:
        infoF("The silence reconnection count: {}", silenceTimeoutCount);
        // We got the silence error. If silence reconnect too many times and the max retries is
        // greater than zero, we will shut down the observer.
        if (config.getSilenceTimeoutRetries() > 0
            && silenceTimeoutCount >= config.getSilenceTimeoutRetries()) {
          infoF(
              "The silence error has happened for too many times:{}. Shutdown the observer",
              silenceTimeoutCount);
          setStatus(NEEDS_SHUTDOWN);
        } else {
          restartInternal();
          lastActivityTime = Instant.now();
          silenceTimeoutCount++;
        }
        break;
      case PUBLISHER_ERROR:
        try {
          infoF(
              "Start the publishing backoff timer: {} seconds",
              publishingBackoffTimer.currentTimer());
          publishingBackoffTimer.backoff();
          restartInternal();
        } catch (InterruptedException e) {
          // if this thread is interrupted, we are shutting down the status watcher.
          setStatus(NEEDS_SHUTDOWN);
        }
        break;
      case DATABASE_ERROR:
        try {
          if (databaseBackoffTimer.isTimerMaxed()) {
            infoF("The database timer is maxed. Shutdown the observer");
            setStatus(NEEDS_SHUTDOWN);
          } else {
            infoF(
                "Start the database backoff timer: {} seconds",
                databaseBackoffTimer.currentTimer());
            databaseBackoffTimer.backoff();
            // now try to connect to database
            restartInternal();
          }
        } catch (InterruptedException e) {
          // if this thread is interrupted, we are shutting down the status watcher.
          setStatus(NEEDS_SHUTDOWN);
        } catch (TransactionException tex) {
          // database is still not available.
          infoF("Still cannot connect to database");
        }
        break;
      case RUNNING:
      case SHUTDOWN:
      default:
        // NOOP
        break;
    }
  }

  boolean isHealthy() {
    return (status == RUNNING);
  }

  /**
   * Load the page token (cursor) from the database. This is used to resume the payment observer
   * from the last processed block.
   *
   * @return the last stored cursor
   */
  String loadPagingToken() {
    info("Loading the last stored cursor from database...");
    String token = paymentStreamerCursorStore.load();
    infoF("The last stored cursor is: {}", token);
    debug("Resetting the database backoff timer...");
    databaseBackoffTimer.reset();

    return token;
  }

  /**
   * Save the page token (cursor) to the database. This is used to resume the payment observer from
   * the last processed block.
   *
   * @param token the last stored cursor
   */
  void savePagingToken(String token) {
    traceF("Saving the last stored cursor to database: {}", token);
    paymentStreamerCursorStore.save(token);
    traceF("Resetting the database backoff timer...");
    databaseBackoffTimer.reset();
  }

  /** The health check result of StellarPaymentObserver class. */
  @Builder
  @Data
  static class SPOHealthCheckResult implements HealthCheckResult {
    transient String name;

    List<HealthCheckStatus> statuses;

    HealthCheckStatus status;

    List<StreamHealth> streams;

    public String name() {
      return name;
    }
  }

  @Data
  @Builder
  static class StreamHealth {
    String account;

    @SerializedName("thread_shutdown")
    boolean threadShutdown;

    @SerializedName("thread_terminated")
    boolean threadTerminated;

    boolean stopped;

    @SerializedName("last_event_id")
    String lastEventId;

    @SerializedName("seconds_since_last_event")
    String silenceSinceLastEvent;
  }

  enum ObserverStatus {
    // healthy
    RUNNING,
    // errors
    DATABASE_ERROR,
    PUBLISHER_ERROR,
    SILENCE_ERROR,
    STREAM_ERROR,
    // shutdown
    NEEDS_SHUTDOWN,
    SHUTDOWN;

    static final Map<ObserverStatus, Set<ObserverStatus>> stateTransition = new HashMap<>();

    // Build the state transition
    static {
      addStateTransition(
          RUNNING,
          DATABASE_ERROR,
          PUBLISHER_ERROR,
          SILENCE_ERROR,
          STREAM_ERROR,
          NEEDS_SHUTDOWN,
          SHUTDOWN);
      addStateTransition(DATABASE_ERROR, RUNNING, NEEDS_SHUTDOWN, SHUTDOWN);
      addStateTransition(PUBLISHER_ERROR, RUNNING, NEEDS_SHUTDOWN, SHUTDOWN);
      addStateTransition(SILENCE_ERROR, RUNNING, NEEDS_SHUTDOWN, SHUTDOWN);
      addStateTransition(STREAM_ERROR, RUNNING, NEEDS_SHUTDOWN, SHUTDOWN);
      addStateTransition(NEEDS_SHUTDOWN, SHUTDOWN);
      addStateTransition(SHUTDOWN, SHUTDOWN);
    }

    static void addStateTransition(ObserverStatus source, ObserverStatus... dests) {
      stateTransition.put(source, Set.of(dests));
    }

    /**
     * Check if the destination state is settable from the current state.
     *
     * @param dest the destination state
     * @return true if the destination state is settable, false otherwise
     */
    public boolean isSettable(ObserverStatus dest) {
      Set<ObserverStatus> dests = stateTransition.get(this);
      return dests != null && dests.contains(dest);
    }
  }
}
