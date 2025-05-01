package org.stellar.anchor.platform.observer.stellar;

import static org.stellar.anchor.api.platform.HealthCheckStatus.*;
import static org.stellar.anchor.api.platform.HealthCheckStatus.RED;
import static org.stellar.anchor.healthcheck.HealthCheckable.Tags.ALL;
import static org.stellar.anchor.healthcheck.HealthCheckable.Tags.EVENT;
import static org.stellar.anchor.platform.observer.stellar.AbstractPaymentObserver.ObserverStatus.*;
import static org.stellar.anchor.util.Log.*;
import static org.stellar.anchor.util.Log.warnF;
import static org.stellar.anchor.util.ReflectionUtil.getField;
import static org.stellar.anchor.util.StringHelper.isEmpty;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.transaction.TransactionException;
import org.stellar.anchor.api.exception.EventPublishException;
import org.stellar.anchor.api.exception.LedgerException;
import org.stellar.anchor.api.platform.HealthCheckResult;
import org.stellar.anchor.api.platform.HealthCheckStatus;
import org.stellar.anchor.ledger.Horizon;
import org.stellar.anchor.ledger.LedgerTransaction;
import org.stellar.anchor.ledger.PaymentTransferEvent;
import org.stellar.anchor.platform.config.PaymentObserverConfig.StellarPaymentObserverConfig;
import org.stellar.anchor.platform.observer.PaymentListener;
import org.stellar.anchor.util.AssetHelper;
import org.stellar.anchor.util.Log;
import org.stellar.sdk.exception.NetworkException;
import org.stellar.sdk.requests.EventListener;
import org.stellar.sdk.requests.PaymentsRequestBuilder;
import org.stellar.sdk.requests.RequestBuilder;
import org.stellar.sdk.requests.SSEStream;
import org.stellar.sdk.responses.Page;
import org.stellar.sdk.responses.operations.OperationResponse;
import org.stellar.sdk.responses.operations.PathPaymentBaseOperationResponse;
import org.stellar.sdk.responses.operations.PaymentOperationResponse;

public class HorizonPaymentObserver extends AbstractPaymentObserver {

  private static final int MAX_RESULTS = 200;

  /** The minimum number of results the Stellar Blockchain can return. */
  private static final int MIN_RESULTS = 1;

  final Horizon horizon;
  SSEStream<OperationResponse> stream;

  public HorizonPaymentObserver(
      String horizonUrl,
      StellarPaymentObserverConfig config,
      List<PaymentListener> paymentListeners,
      PaymentObservingAccountsManager paymentObservingAccountsManager,
      StellarPaymentStreamerCursorStore paymentStreamerCursorStore) {
    super(config, paymentListeners, paymentObservingAccountsManager, paymentStreamerCursorStore);
    this.horizon = new Horizon(horizonUrl);
  }

  /** Start the observer. */
  @Override
  void startInternal() {
    infoF("Starting the SSEStream");
    startStream();
  }

  /** Graceful shut down the observer */
  @Override
  void shutdownInternal() {
    infoF("Shutting down the SSEStream");
    stopStream();
  }

  void startStream() {
    this.stream = startSSEStream();
  }

  SSEStream<OperationResponse> startSSEStream() {
    String latestCursor = fetchStreamingCursor();
    infoF("SSEStream cursor={}", latestCursor);

    PaymentsRequestBuilder paymentsRequest =
        horizon
            .getServer()
            .payments()
            .cursor(latestCursor)
            .order(RequestBuilder.Order.ASC)
            .limit(MAX_RESULTS);
    return paymentsRequest.stream(
        new EventListener<>() {
          @Override
          public void onEvent(OperationResponse operationResponse) {
            if (isHealthy()) {
              debugF("Received payment {}", operationResponse.getId());
              // clear stream timeout/reconnect status
              lastActivityTime = Instant.now();
              silenceTimeoutCount = 0;
              streamBackoffTimer.reset();
              try {
                processOperation(operationResponse);
              } catch (TransactionException ex) {
                errorEx("Error handling events", ex);
                setStatus(DATABASE_ERROR);
              }
            } else {
              warnF("Observer is not healthy. Ignore event {}", operationResponse.getId());
            }
          }

          @Override
          public void onFailure(Optional<Throwable> error, Optional<Integer> responseCode) {
            handleFailure(error.orElse(null));
          }
        });
  }

  void handleFailure(Throwable error) {
    // The SSEStreamer has internal errors. We will give up and let the container
    // manager to restart.
    errorEx("stellar payment observer stream error: ", error);
    // Mark the observer unhealthy
    setStatus(STREAM_ERROR);
  }

  void stopStream() {
    if (this.stream != null) {
      info("Stopping the stream");
      this.stream.close();
      this.stream = null;
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
          horizon
              .getServer()
              .payments()
              .order(RequestBuilder.Order.DESC)
              .limit(MIN_RESULTS)
              .execute();
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

  void processOperation(OperationResponse operationResponse) {
    try {
      PaymentTransferEvent transferEvent = toPaymentTransferEvent(operationResponse);
      if (transferEvent != null) {
        handleEvent(transferEvent);
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

  /**
   * Convert the operation to a PaymentTransferEvent.
   *
   * @param operation the Horizon operation to convert
   * @return the PaymentTransferEvent
   * @throws LedgerException if there is an error fetching the transaction
   */
  PaymentTransferEvent toPaymentTransferEvent(OperationResponse operation) throws LedgerException {
    if (!(operation instanceof PaymentOperationResponse
        || operation instanceof PathPaymentBaseOperationResponse)) {
      // only fetch transaction if the operation is a payment operation
      return null;
    }

    LedgerTransaction txn = horizon.getTransaction(operation.getTransactionHash());
    if (txn == null) {
      debugF("Transaction not found: {}", operation.getTransactionHash());
      return null;
    }
    if (operation instanceof PaymentOperationResponse paymentOp) {
      return PaymentTransferEvent.builder()
          .from(paymentOp.getFrom())
          .to(paymentOp.getTo())
          .sep11Asset(AssetHelper.getSep11AssetName(paymentOp.getAsset().toXdr()))
          .amount(AssetHelper.toXdrAmount(paymentOp.getAmount()))
          .operationId(operation.getId())
          .txHash(paymentOp.getTransactionHash())
          .ledgerTransaction(txn)
          .build();
    } else {
      PathPaymentBaseOperationResponse pathPaymentOp = (PathPaymentBaseOperationResponse) operation;
      return PaymentTransferEvent.builder()
          .from(pathPaymentOp.getFrom())
          .to(pathPaymentOp.getTo())
          .sep11Asset(AssetHelper.getSep11AssetName(pathPaymentOp.getAsset().toXdr()))
          .amount(AssetHelper.toXdrAmount(pathPaymentOp.getAmount()))
          .operationId(operation.getId())
          .txHash(pathPaymentOp.getTransactionHash())
          .ledgerTransaction(txn)
          .build();
    }
  }

  @Override
  public String getName() {
    return "horizon_payment_observer";
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
