package org.stellar.anchor.platform.observer.stellar;

import static io.micrometer.core.instrument.Metrics.gauge;
import static org.stellar.anchor.api.platform.HealthCheckStatus.*;
import static org.stellar.anchor.healthcheck.HealthCheckable.Tags.ALL;
import static org.stellar.anchor.healthcheck.HealthCheckable.Tags.EVENT;
import static org.stellar.anchor.platform.observer.stellar.ObserverStatus.*;
import static org.stellar.anchor.util.Log.*;
import static org.stellar.anchor.util.MetricConstants.*;
import static org.stellar.anchor.util.ReflectionUtil.getField;
import static org.stellar.anchor.util.StringHelper.isEmpty;

import com.google.gson.annotations.SerializedName;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import lombok.Builder;
import lombok.Data;
import org.jetbrains.annotations.NotNull;
import org.springframework.transaction.TransactionException;
import org.stellar.anchor.api.exception.EventPublishException;
import org.stellar.anchor.api.platform.HealthCheckResult;
import org.stellar.anchor.api.platform.HealthCheckStatus;
import org.stellar.anchor.healthcheck.HealthCheckable;
import org.stellar.anchor.ledger.Horizon;
import org.stellar.anchor.ledger.LedgerTransaction;
import org.stellar.anchor.ledger.LedgerTransaction.LedgerOperation;
import org.stellar.anchor.platform.config.PaymentObserverConfig;
import org.stellar.anchor.platform.observer.ObservedPayment;
import org.stellar.anchor.platform.observer.PaymentListener;
import org.stellar.anchor.platform.utils.DaemonExecutors;
import org.stellar.anchor.util.ExponentialBackoffTimer;
import org.stellar.anchor.util.Log;
import org.stellar.sdk.Server;
import org.stellar.sdk.exception.NetworkException;
import org.stellar.sdk.requests.EventListener;
import org.stellar.sdk.requests.PaymentsRequestBuilder;
import org.stellar.sdk.requests.RequestBuilder;
import org.stellar.sdk.requests.SSEStream;
import org.stellar.sdk.responses.Page;
import org.stellar.sdk.responses.operations.OperationResponse;

public class StellarPaymentObserver implements HealthCheckable {
  /** The maximum number of results the Stellar Blockchain can return. */
  private static final int MAX_RESULTS = 200;

  /** The minimum number of results the Stellar Blockchain can return. */
  private static final int MIN_RESULTS = 1;

  final Server server;
  final PaymentObserverConfig.StellarPaymentObserverConfig config;
  final List<PaymentListener> paymentListeners;
  final StellarPaymentStreamerCursorStore paymentStreamerCursorStore;
  final Map<SSEStream<OperationResponse>, String> mapStreamToAccount = new HashMap<>();
  final PaymentObservingAccountsManager paymentObservingAccountsManager;
  SSEStream<OperationResponse> stream;

  final ExponentialBackoffTimer publishingBackoffTimer;
  final ExponentialBackoffTimer streamBackoffTimer;
  final ExponentialBackoffTimer databaseBackoffTimer = new ExponentialBackoffTimer(1, 20);

  int silenceTimeoutCount = 0;
  ObserverStatus status = RUNNING;
  Instant lastActivityTime;
  AtomicLong metricLatestBlockRead = new AtomicLong(0);
  AtomicLong metricLatestBlockProcessed = new AtomicLong(0);

  final ScheduledExecutorService silenceWatcher = DaemonExecutors.newScheduledThreadPool(1);
  final ScheduledExecutorService statusWatcher = DaemonExecutors.newScheduledThreadPool(1);

  public StellarPaymentObserver(
      String horizonServer,
      PaymentObserverConfig.StellarPaymentObserverConfig config,
      List<PaymentListener> paymentListeners,
      PaymentObservingAccountsManager paymentObservingAccountsManager,
      StellarPaymentStreamerCursorStore paymentStreamerCursorStore) {
    this.server = new Server(horizonServer);
    this.config = config;
    this.paymentListeners = paymentListeners;
    this.paymentObservingAccountsManager = paymentObservingAccountsManager;
    this.paymentStreamerCursorStore = paymentStreamerCursorStore;

    publishingBackoffTimer =
        new ExponentialBackoffTimer(
            config.getInitialEventBackoffTime(), config.getMaxEventBackoffTime());
    streamBackoffTimer =
        new ExponentialBackoffTimer(
            config.getInitialStreamBackoffTime(), config.getMaxStreamBackoffTime());

    // register gauges
    gauge(PAYMENT_OBSERVER_LATEST_BLOCK_READ, metricLatestBlockRead);
    gauge(PAYMENT_OBSERVER_LATEST_BLOCK_PROCESSED, metricLatestBlockProcessed);
  }

  /** Start the observer. */
  public void start() {
    infoF("Starting the SSEStream");
    startStream();

    infoF("Starting the observer silence watcher");
    silenceWatcher.scheduleAtFixedRate(
        this::checkSilence, 1, config.getSilenceCheckInterval(), TimeUnit.SECONDS);

    infoF("Starting the status watcher");
    statusWatcher.scheduleWithFixedDelay(this::checkStatus, 1, 1, TimeUnit.SECONDS);

    setStatus(RUNNING);
  }

  /** Graceful shut down the observer */
  public void shutdown() {
    infoF("Shutting down the SSEStream");
    stopStream();

    infoF("Stopping the silence watcher");
    silenceWatcher.shutdown();

    infoF("Stopping the status watcher");
    statusWatcher.shutdown();
    setStatus(SHUTDOWN);
  }

  void startStream() {
    this.stream = startSSEStream();
  }

  SSEStream<OperationResponse> startSSEStream() {
    String latestCursor = fetchStreamingCursor();
    infoF("SSEStream cursor={}", latestCursor);

    PaymentsRequestBuilder paymentsRequest =
        server
            .payments()
            .includeTransactions(true)
            .cursor(latestCursor)
            .order(RequestBuilder.Order.ASC)
            .limit(MAX_RESULTS);
    return paymentsRequest.stream(
        new EventListener<>() {
          @Override
          public void onEvent(OperationResponse operationResponse) {
            if (operationResponse.getTransaction() != null) {
              metricLatestBlockRead.set(operationResponse.getTransaction().getLedger());

              if (isHealthy()) {
                debugF("Received event {}", operationResponse.getId());
                // clear stream timeout/reconnect status
                lastActivityTime = Instant.now();
                silenceTimeoutCount = 0;
                streamBackoffTimer.reset();
                try {
                  debugF("Dispatching event {}", operationResponse.getId());
                  handleEvent(operationResponse);
                  metricLatestBlockProcessed.set(operationResponse.getTransaction().getLedger());

                } catch (TransactionException ex) {
                  errorEx("Error handling events", ex);
                  setStatus(DATABASE_ERROR);
                }
              } else {
                warnF("Observer is not healthy. Ignore event {}", operationResponse.getId());
              }
            }
          }

          @Override
          public void onFailure(Optional<Throwable> error, Optional<Integer> responseCode) {
            handleFailure(error);
          }
        });
  }

  void stopStream() {
    if (this.stream != null) {
      info("Stopping the stream");
      this.stream.close();
      this.stream = null;
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

  void restartStream() {
    try {
      infoF("Restarting the stream");
      stopStream();
      startStream();
      setStatus(RUNNING);
    } catch (TransactionException tex) {
      errorEx("Error restarting stream.", tex);
      setStatus(DATABASE_ERROR);
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
            restartStream();
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
          restartStream();
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
          restartStream();
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
            restartStream();
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

  /**
   * fetchStreamingCursor will gather a starting cursor for the streamer. If there is a cursor
   * already stored in the database, that value will be returned. Otherwise, this method will fetch
   * the most recent cursor from the Network and use that as a starting point.
   *
   * @return the starting point to start streaming from.
   */
  String fetchStreamingCursor() {
    // Use database value, if any.
    String strLastStored = loadPagingToken();
    String strLatestFromNetwork = fetchLatestCursorFromNetwork();
    Log.infoF("The latest cursor fetched from Stellar network is: {}", strLatestFromNetwork);
    if (isEmpty(strLastStored)) {
      info("No last stored cursor, so use the latest cursor");
      return strLatestFromNetwork;
    } else {
      long lastStored = Long.parseLong(strLastStored);
      long latest = Long.parseLong(strLatestFromNetwork);
      if (lastStored >= latest) {
        infoF(
            "The last stored cursor is stale. This is probably because of a test network reset. Use the latest cursor: {}",
            strLatestFromNetwork);
        return String.valueOf(latest);
      } else {
        return String.valueOf(Math.max(lastStored, latest - MAX_RESULTS));
      }
    }
  }

  String fetchLatestCursorFromNetwork() {
    // Fetch the latest cursor from the stellar network
    Page<OperationResponse> pageOpResponse;
    try {
      infoF("Fetching the latest payments records. (limit={})", MIN_RESULTS);
      pageOpResponse =
          server.payments().order(RequestBuilder.Order.DESC).limit(MIN_RESULTS).execute();
    } catch (NetworkException e) {
      Log.errorEx("Error fetching the latest /payments result.", e);
      return null;
    }

    if (pageOpResponse == null
        || pageOpResponse.getRecords() == null
        || pageOpResponse.getRecords().isEmpty()) {
      info("No payments found.");
      return null;
    }
    String token = pageOpResponse.getRecords().get(0).getPagingToken();
    infoF("The latest cursor fetched from Stellar network is: {}", token);
    return token;
  }

  void handleEvent(OperationResponse operationResponse) {
    if (!operationResponse.getTransactionSuccessful()) {
      savePagingToken(operationResponse.getPagingToken());
      return;
    }
    try {
      LedgerOperation ledgerOperation = Horizon.toLedgerOperation(operationResponse);
      if (ledgerOperation == null) {
        return;
      }
      LedgerTransaction ledgerTxn =
          Horizon.toLedgerTransaction(server, operationResponse.getTransaction());
      ObservedPayment observedPayment =
          switch (ledgerOperation.getType()) {
            case PAYMENT -> ObservedPayment.from(ledgerTxn, ledgerOperation.getPaymentOperation());
            case PATH_PAYMENT_STRICT_RECEIVE, PATH_PAYMENT_STRICT_SEND ->
                ObservedPayment.from(ledgerTxn, ledgerOperation.getPathPaymentOperation());
            default ->
                throw new IllegalStateException("Unexpected value: " + ledgerOperation.getType());
          };

      if (observedPayment != null) {
        if (paymentObservingAccountsManager.lookupAndUpdate(observedPayment.getTo())) {
          for (PaymentListener listener : paymentListeners) {
            listener.onReceived(observedPayment);
          }
        }

        if (paymentObservingAccountsManager.lookupAndUpdate(observedPayment.getFrom())
            && !observedPayment.getTo().equals(observedPayment.getFrom())) {
          for (PaymentListener listener : paymentListeners) {
            listener.onSent(observedPayment);
          }
        }
        publishingBackoffTimer.reset();
        paymentStreamerCursorStore.save(operationResponse.getPagingToken());
      }
    } catch (EventPublishException ex) {
      // restart the observer from where it stopped, in case the queue fails to
      // publish the message.
      errorEx("Failed to send event to payment listeners.", ex);
      setStatus(PUBLISHER_ERROR);
    } catch (TransactionException tex) {
      errorEx("Cannot save the cursor to database", tex);
      setStatus(DATABASE_ERROR);
    } catch (Throwable t) {
      errorEx("Something went wrong in the observer while sending the event", t);
      setStatus(PUBLISHER_ERROR);
    } finally {
      savePagingToken(operationResponse.getPagingToken());
    }
  }

  void handleFailure(Optional<Throwable> throwable) {
    // The SSEStreamer has internal errors. We will give up and let the container
    // manager to restart.
    errorEx("stellar payment observer stream error: ", throwable.orElse(null));

    // Mark the observer unhealthy
    setStatus(STREAM_ERROR);
  }

  String loadPagingToken() {
    info("Loading the last stored cursor from database...");
    String token = paymentStreamerCursorStore.load();
    infoF("The last stored cursor is: {}", token);
    debug("Resetting the database backoff timer...");
    databaseBackoffTimer.reset();

    return token;
  }

  void savePagingToken(String token) {
    traceF("Saving the last stored cursor to database: {}", token);
    paymentStreamerCursorStore.save(token);
    traceF("Resetting the database backoff timer...");
    databaseBackoffTimer.reset();
  }

  void setStatus(ObserverStatus status) {
    if (this.status != status) {
      if (this.status.isSettable(status)) {
        infoF("Setting status to {}", status);
        this.status = status;
      } else {
        warnF("Cannot set status to {} while the current status is {}", status, this.status);
      }
    }
  }

  boolean isHealthy() {
    return (status == RUNNING);
  }

  @Override
  public int compareTo(@NotNull HealthCheckable other) {
    return this.getName().compareTo(other.getName());
  }

  @Override
  public String getName() {
    return "stellar_payment_observer";
  }

  @Override
  public List<Tags> getTags() {
    return List.of(ALL, EVENT);
  }

  @Override
  public HealthCheckResult check() {
    List<StreamHealth> results = new ArrayList<>();

    HealthCheckStatus status =
        switch (this.status) {
          case STREAM_ERROR, SILENCE_ERROR, PUBLISHER_ERROR, DATABASE_ERROR -> YELLOW;
          case NEEDS_SHUTDOWN, SHUTDOWN -> RED;
          default -> GREEN;
        };
    StreamHealth.StreamHealthBuilder healthBuilder = StreamHealth.builder();
    healthBuilder.account(mapStreamToAccount.get(stream));
    // populate executorService information
    if (stream != null) {
      ExecutorService executorService = getField(stream, "executorService", null);
      if (executorService != null) {
        healthBuilder.threadShutdown(executorService.isShutdown());
        healthBuilder.threadTerminated(executorService.isTerminated());
        if (executorService.isShutdown() || executorService.isTerminated()) {
          status = RED;
        }
      } else {
        status = RED;
      }

      AtomicBoolean isStopped = getField(stream, "isStopped", new AtomicBoolean(false));
      if (isStopped != null) {
        healthBuilder.stopped(isStopped.get());
        if (isStopped.get()) {
          status = RED;
        }
      }

      AtomicReference<String> lastEventId = getField(stream, "lastEventId", null);
      if (lastEventId != null && lastEventId.get() != null) {
        healthBuilder.lastEventId(lastEventId.get());
      } else {
        healthBuilder.lastEventId("-1");
      }
    }

    if (lastActivityTime == null) {
      healthBuilder.silenceSinceLastEvent("0");
    } else {
      healthBuilder.silenceSinceLastEvent(
          String.valueOf(Duration.between(lastActivityTime, Instant.now()).getSeconds()));
    }

    results.add(healthBuilder.build());

    return SPOHealthCheckResult.builder().name(getName()).streams(results).status(status).build();
  }
}

/** The health check result of StellarPaymentObserver class. */
@Builder
@Data
class SPOHealthCheckResult implements HealthCheckResult {
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
class StreamHealth {
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

  public boolean isSettable(ObserverStatus dest) {
    Set<ObserverStatus> dests = stateTransition.get(this);
    return dests != null && dests.contains(dest);
  }
}
