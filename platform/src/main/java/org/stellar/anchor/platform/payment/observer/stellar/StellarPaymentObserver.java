package org.stellar.anchor.platform.payment.observer.stellar;

import static org.stellar.anchor.api.platform.HealthCheckStatus.*;
import static org.stellar.anchor.platform.payment.observer.stellar.ObserverStatus.*;
import static org.stellar.anchor.platform.service.AnchorMetrics.*;
import static org.stellar.anchor.util.Log.*;
import static org.stellar.anchor.util.ReflectionUtil.getField;

import com.google.gson.annotations.SerializedName;
import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.Tags;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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
import org.stellar.anchor.api.exception.SepException;
import org.stellar.anchor.api.exception.ValueValidationException;
import org.stellar.anchor.api.platform.HealthCheckResult;
import org.stellar.anchor.api.platform.HealthCheckStatus;
import org.stellar.anchor.healthcheck.HealthCheckable;
import org.stellar.anchor.platform.payment.observer.PaymentListener;
import org.stellar.anchor.platform.payment.observer.circle.ObservedPayment;
import org.stellar.anchor.util.ExponentialBackoffTimer;
import org.stellar.anchor.util.Log;
import org.stellar.sdk.Server;
import org.stellar.sdk.requests.EventListener;
import org.stellar.sdk.requests.PaymentsRequestBuilder;
import org.stellar.sdk.requests.RequestBuilder;
import org.stellar.sdk.requests.SSEStream;
import org.stellar.sdk.responses.Page;
import org.stellar.sdk.responses.operations.OperationResponse;
import org.stellar.sdk.responses.operations.PathPaymentBaseOperationResponse;
import org.stellar.sdk.responses.operations.PaymentOperationResponse;

public class StellarPaymentObserver implements HealthCheckable {
  /** The maximum number of results the Stellar Blockchain can return. */
  private static final int MAX_RESULTS = 200;

  /** The minimum number of results the Stellar Blockchain can return. */
  private static final int MIN_RESULTS = 1;

  /**
   * If the observer had been silent for longer than SILENC_TIMEOUT, a SilenceTimeoutException will
   * be thrown to trigger reconnections.
   */
  private static final long SILENCE_TIMEOUT = 90;

  /** If the observer has more than 2 SILENCE_TIMEOUT_RETRIES, it will be marked unhealthy */
  private static final long SILENCE_TIMEOUT_RETRIES = 2;

  /** The time interval between silence checks */
  private static final long SILENCE_CHECK_INTERVAL = 5;

  // Horizon server
  final Server server;
  final Set<PaymentListener> paymentListeners;
  final StellarPaymentStreamerCursorStore paymentStreamerCursorStore;
  final Map<SSEStream<OperationResponse>, String> mapStreamToAccount = new HashMap<>();
  final PaymentObservingAccountsManager paymentObservingAccountsManager;
  SSEStream<OperationResponse> stream;
  int silenceTimeoutCount = 0;
  ObserverStatus status = RUNNING;
  Instant lastActivityTime;
  AtomicLong metricLatestBlockReceived = null;
  AtomicLong metricLatestBlockProcessed = null;
  // timers
  final ExponentialBackoffTimer publishingBackoffTimer = new ExponentialBackoffTimer();
  final ExponentialBackoffTimer streamBackoffTimer = new ExponentialBackoffTimer();
  final ExponentialBackoffTimer databaseBackoffTimer = new ExponentialBackoffTimer(1, 20);

  // schedulers
  ScheduledExecutorService silenceWatcher = Executors.newSingleThreadScheduledExecutor();
  ScheduledExecutorService statusWatcher = Executors.newSingleThreadScheduledExecutor();

  StellarPaymentObserver(
      String horizonServer,
      Set<PaymentListener> paymentListeners,
      PaymentObservingAccountsManager paymentObservingAccountsManager,
      StellarPaymentStreamerCursorStore paymentStreamerCursorStore) {
    this.server = new Server(horizonServer);
    this.paymentListeners = paymentListeners;
    this.paymentObservingAccountsManager = paymentObservingAccountsManager;
    this.paymentStreamerCursorStore = paymentStreamerCursorStore;
  }

  /** Start the observer. */
  public void start() {
    infoF("Starting the SSEStream");
    startStream();

    infoF("Starting the observer silence watcher");
    silenceWatcher.scheduleAtFixedRate(
        this::checkSilence,
        1,
        SILENCE_CHECK_INTERVAL,
        TimeUnit.SECONDS); // TODO: The period should be made configurable in version 2.x

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
    debugF("SSEStream last cursor={}", latestCursor);

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
            // update the received metrics when the events are received and before they are handled.
            updateReceivedMetrics(operationResponse);
            if (isHealthy()) {
              debugF("Received event {}", operationResponse.getId());
              // clear stream timeout/reconnect status
              lastActivityTime = Instant.now();
              silenceTimeoutCount = 0;
              streamBackoffTimer.reset();
              try {
                debugF("Dispatching event {}", operationResponse.getId());
                handleEvent(operationResponse);
                // update processed metrics when the events are handled successfully.
                updateProcessedMetrics(operationResponse);
              } catch (TransactionException ex) {
                errorEx("Error handling events", ex);
                setStatus(DATABASE_ERROR);
              }
            } else {
              warnF("Observer is not healthy. Ignore event {}", operationResponse.getId());
            }
          }

          @Override
          public void onFailure(
              java.util.Optional<Throwable> error, java.util.Optional<Integer> responseCode) {
            handleFailure(error);
          }
        },
        SILENCE_TIMEOUT * 1000);
  }

  private void updateReceivedMetrics(OperationResponse operationResponse) {
    if (metricLatestBlockReceived == null && operationResponse.getTransaction().isPresent()) {
      metricLatestBlockReceived =
          new AtomicLong(operationResponse.getTransaction().get().getLedger());
      Metrics.gauge(
          STELLAR_PAYMENT_OBSERVER.toString(),
          Tags.of("status", TAG_OBSERVER_LATEST_BLOCK_RECEIVED.toString()),
          metricLatestBlockReceived);
    }
    Log.debugF(
        "Update metrics {}{status=\"{}\"}: {}",
        STELLAR_PAYMENT_OBSERVER,
        TAG_OBSERVER_LATEST_BLOCK_RECEIVED,
        operationResponse.getTransaction().get().getLedger());
    metricLatestBlockReceived.set(operationResponse.getTransaction().get().getLedger());
  }

  private void updateProcessedMetrics(OperationResponse operationResponse) {
    if (metricLatestBlockProcessed == null && operationResponse.getTransaction().isPresent()) {
      metricLatestBlockProcessed =
          new AtomicLong(operationResponse.getTransaction().get().getLedger());
      Metrics.gauge(
          STELLAR_PAYMENT_OBSERVER.toString(),
          Tags.of("status", TAG_OBSERVER_LATEST_BLOCK_PROCESSED.toString()),
          metricLatestBlockProcessed);
    }
    Log.debugF(
        "Update metrics {}{status=\"{}\"}: {}",
        STELLAR_PAYMENT_OBSERVER,
        TAG_OBSERVER_LATEST_BLOCK_PROCESSED,
        operationResponse.getTransaction().get().getLedger());
    metricLatestBlockProcessed.set(operationResponse.getTransaction().get().getLedger());
  }

  void stopStream() {
    if (this.stream != null) {
      this.stream.close();
      this.stream = null;
    }
  }

  void checkSilence() {
    if (isHealthy()) {
      Instant now = Instant.now();
      if (lastActivityTime != null) {
        Duration silenceDuration = Duration.between(lastActivityTime, now);
        if (silenceDuration.getSeconds() > SILENCE_TIMEOUT) {
          infoF("The observer had been silent for {} seconds.", silenceDuration.getSeconds());
          setStatus(SILENCE_ERROR);
        } else {
          debugF("The observer had been silent for {} seconds.", silenceDuration.getSeconds());
        }
      }
    }
  }

  void restartStream() {
    infoF("Restarting the stream");
    try {
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
            streamBackoffTimer.sleep();
            streamBackoffTimer.increase();
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
        // We got the silence error. If silence reconnect too many times, we will shut down the
        // observer.
        if (silenceTimeoutCount >= SILENCE_TIMEOUT_RETRIES) {
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
          if (publishingBackoffTimer.isTimerMaxed()) {
            infoF("The streamer publisher timer is maxed. Shutdown the observer");
            setStatus(NEEDS_SHUTDOWN);
          } else {
            infoF(
                "Start the publishing backoff timer: {} seconds",
                publishingBackoffTimer.currentTimer());
            publishingBackoffTimer.sleep();
            publishingBackoffTimer.increase();
            restartStream();
          }
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
            databaseBackoffTimer.sleep();
            databaseBackoffTimer.increase();
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
    String lastToken = loadPagingToken();
    // now that the database connection is good, we are resetting the backoff timer
    if (lastToken != null) {
      return lastToken;
    }

    // Otherwise, fetch the latest value from the network.
    Page<OperationResponse> pageOpResponse;
    try {
      pageOpResponse =
          server.payments().order(RequestBuilder.Order.DESC).limit(MIN_RESULTS).execute();
    } catch (IOException e) {
      errorEx("Error fetching the latest /payments result.", e);
      return null;
    }

    if (pageOpResponse == null
        || pageOpResponse.getRecords() == null
        || pageOpResponse.getRecords().size() == 0) {
      return null;
    }
    return pageOpResponse.getRecords().get(0).getPagingToken();
  }

  void handleEvent(OperationResponse operationResponse) {
    if (!operationResponse.isTransactionSuccessful()) {
      savePagingToken(operationResponse.getPagingToken());
      return;
    }

    ObservedPayment observedPayment = null;
    try {
      if (operationResponse instanceof PaymentOperationResponse) {
        PaymentOperationResponse payment = (PaymentOperationResponse) operationResponse;
        observedPayment = ObservedPayment.fromPaymentOperationResponse(payment);
      } else if (operationResponse instanceof PathPaymentBaseOperationResponse) {
        PathPaymentBaseOperationResponse pathPayment =
            (PathPaymentBaseOperationResponse) operationResponse;
        observedPayment = ObservedPayment.fromPathPaymentOperationResponse(pathPayment);
      }
    } catch (SepException ex) {
      if (operationResponse.getTransaction().isPresent()) {
        Log.warn(
            String.format(
                "Payment of id %s contains unsupported memo %s.",
                operationResponse.getId(),
                operationResponse.getTransaction().get().getMemo().toString()));
      }
      Log.warnEx(ex);
    }

    if (observedPayment == null) {
      savePagingToken(operationResponse.getPagingToken());
    } else {
      try {
        if (paymentObservingAccountsManager.lookupAndUpdate(observedPayment.getTo())) {
          for (PaymentListener listener : paymentListeners) {
            listener.onReceived(observedPayment);
          }
        }

        if (paymentObservingAccountsManager.lookupAndUpdate(observedPayment.getFrom())
            && !observedPayment.getTo().equals(observedPayment.getFrom())) {
          final ObservedPayment finalObservedPayment = observedPayment;
          paymentListeners.forEach(observer -> observer.onSent(finalObservedPayment));
        }

        publishingBackoffTimer.reset();
        paymentStreamerCursorStore.save(operationResponse.getPagingToken());
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
      }
    }
  }

  void handleFailure(Optional<Throwable> exception) {
    // The SSEStreamer has internal errors. We will give up and let the container
    // manager to restart.
    if (exception.isPresent()
        && exception.get() instanceof IOException
        && exception.get().getMessage().contains("Canceled")) {
      infoF("Restarting stream");
      return;
    }
    exception.ifPresent(throwable -> errorEx("stellar payment observer stream error: ", throwable));
    // Mark the observer unhealthy
    setStatus(STREAM_ERROR);
  }

  private String loadPagingToken() {
    String token = paymentStreamerCursorStore.load();
    databaseBackoffTimer.reset();
    return token;
  }

  void savePagingToken(String token) {
    paymentStreamerCursorStore.save(token);
    databaseBackoffTimer.reset();
  }

  void setStatus(ObserverStatus status) {
    if (this.status != status) {
      if (this.status.isSettable(status)) {
        debugF("Setting status to {}", status);
        this.status = status;
      } else {
        warnF("Cannot set status to {} while the current status is {}", status, this.status);
      }
    }
  }

  boolean isHealthy() {
    return (status == RUNNING);
  }

  public static Builder builder() {
    return new Builder();
  }

  @Override
  public int compareTo(@NotNull HealthCheckable other) {
    return other.getName().compareTo(other.getName());
  }

  public static class Builder {
    String horizonServer = "https://horizon-testnet.stellar.org";
    Set<PaymentListener> observers = new HashSet<>();
    StellarPaymentStreamerCursorStore paymentStreamerCursorStore =
        new MemoryStellarPaymentStreamerCursorStore();
    private PaymentObservingAccountsManager paymentObservingAccountsManager;

    public Builder() {}

    public Builder horizonServer(String horizonServer) {
      this.horizonServer = horizonServer;
      return this;
    }

    public Builder observers(List<PaymentListener> observers) {
      this.observers.addAll(observers);
      return this;
    }

    public Builder paymentTokenStore(
        StellarPaymentStreamerCursorStore stellarPaymentStreamerCursorStore) {
      this.paymentStreamerCursorStore = stellarPaymentStreamerCursorStore;
      return this;
    }

    public Builder paymentObservingAccountManager(
        PaymentObservingAccountsManager paymentObservingAccountsManager) {
      this.paymentObservingAccountsManager = paymentObservingAccountsManager;
      return this;
    }

    public StellarPaymentObserver build() throws ValueValidationException {
      return new StellarPaymentObserver(
          horizonServer, observers, paymentObservingAccountsManager, paymentStreamerCursorStore);
    }
  }

  @Override
  public String getName() {
    return "stellar_payment_observer";
  }

  @Override
  public List<String> getTags() {
    return List.of("all", "event");
  }

  @Override
  public HealthCheckResult check() {
    List<StreamHealth> results = new ArrayList<>();

    HealthCheckStatus status;
    switch (this.status) {
      case STREAM_ERROR:
      case SILENCE_ERROR:
      case PUBLISHER_ERROR:
      case DATABASE_ERROR:
        status = YELLOW;
        break;
      case NEEDS_SHUTDOWN:
      case SHUTDOWN:
        status = RED;
        break;
      case RUNNING:
      default:
        status = GREEN;
        break;
    }

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
