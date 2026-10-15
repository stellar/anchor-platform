package org.stellar.anchor.platform.observer.stellar;

import static org.stellar.anchor.api.platform.HealthCheckStatus.*;
import static org.stellar.anchor.api.platform.HealthCheckStatus.RED;
import static org.stellar.anchor.healthcheck.HealthCheckable.Tags.ALL;
import static org.stellar.anchor.healthcheck.HealthCheckable.Tags.EVENT;
import static org.stellar.anchor.platform.observer.stellar.StellarRpcPaymentObserver.ShouldProcessResult.*;
import static org.stellar.anchor.util.Log.*;
import static org.stellar.anchor.util.StringHelper.isEmpty;

import io.micrometer.core.instrument.Metrics;
import java.io.IOException;
import java.math.BigInteger;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.stream.Stream;
import lombok.Builder;
import lombok.Getter;
import org.stellar.anchor.api.asset.AssetInfo;
import org.stellar.anchor.api.asset.StellarAssetInfo;
import org.stellar.anchor.api.exception.AnchorException;
import org.stellar.anchor.api.exception.LedgerDecodeException;
import org.stellar.anchor.api.exception.LedgerException;
import org.stellar.anchor.api.platform.HealthCheckResult;
import org.stellar.anchor.api.platform.HealthCheckStatus;
import org.stellar.anchor.asset.AssetService;
import org.stellar.anchor.ledger.LedgerClientHelper;
import org.stellar.anchor.ledger.LedgerTransaction;
import org.stellar.anchor.ledger.LedgerTransaction.LedgerOperation;
import org.stellar.anchor.ledger.LedgerTransaction.LedgerPathPaymentOperation;
import org.stellar.anchor.ledger.LedgerTransaction.LedgerPaymentOperation;
import org.stellar.anchor.ledger.PaymentTransferEvent;
import org.stellar.anchor.ledger.StellarRpc;
import org.stellar.anchor.platform.config.PaymentObserverConfig.StellarPaymentObserverConfig;
import org.stellar.anchor.platform.observer.PaymentListener;
import org.stellar.anchor.platform.service.AnchorMetrics;
import org.stellar.anchor.util.AssetHelper;
import org.stellar.anchor.util.GsonUtils;
import org.stellar.anchor.util.Log;
import org.stellar.sdk.MuxedAccount;
import org.stellar.sdk.SorobanServer;
import org.stellar.sdk.TOID;
import org.stellar.sdk.exception.NetworkException;
import org.stellar.sdk.exception.SorobanRpcException;
import org.stellar.sdk.requests.sorobanrpc.EventFilterType;
import org.stellar.sdk.requests.sorobanrpc.GetEventsRequest;
import org.stellar.sdk.requests.sorobanrpc.GetEventsRequest.EventFilter;
import org.stellar.sdk.responses.sorobanrpc.GetEventsResponse;
import org.stellar.sdk.responses.sorobanrpc.GetEventsResponse.EventInfo;
import org.stellar.sdk.responses.sorobanrpc.GetLatestLedgerResponse;
import org.stellar.sdk.scval.Scv;
import org.stellar.sdk.xdr.Asset;
import org.stellar.sdk.xdr.SCVal;
import org.stellar.sdk.xdr.SCValType;

public class StellarRpcPaymentObserver extends AbstractPaymentObserver {
  @Getter final StellarRpc stellarRpc;
  @Getter final SorobanServer sorobanServer;
  final SacToAssetMapper sacToAssetMapper;
  final AssetService assetService;

  /** The maximum number of filters stellar-rpc accepts in one getEvents request. */
  static final int MAX_FILTERS_PER_REQUEST = 5;

  /** The number of consecutive rejected polls after which the observer goes to STREAM_ERROR. */
  static final int RPC_REJECTION_LIMIT = 5;

  volatile int consecutiveFailedPolls = 0;

  /**
   * The last cursor each request (chunk of filters) completed, by chunk index. Kept in memory only:
   * the persisted cursor is the minimum of these, so a restart safely re-reads from it. A request
   * that is ahead of the others resumes from its own cursor instead of re-reading events it has
   * already processed.
   */
  final Map<Integer, String> chunkCursors = new ConcurrentHashMap<>();

  int chunkCount = 0;

  public StellarRpcPaymentObserver(
      StellarRpc stellarRpc,
      StellarPaymentObserverConfig config,
      List<PaymentListener> paymentListeners,
      PaymentObservingAccountsManager paymentObservingAccountsManager,
      StellarPaymentStreamerCursorStore paymentStreamerCursorStore,
      SacToAssetMapper sacToAssetMapper,
      AssetService assetService) {
    super(config, paymentListeners, paymentObservingAccountsManager, paymentStreamerCursorStore);
    this.stellarRpc = stellarRpc;
    this.assetService = assetService;
    this.sorobanServer = stellarRpc.getSorobanServer();
    this.sacToAssetMapper = sacToAssetMapper;
    this.status = ObserverStatus.STARTING;
  }

  @Override
  void startInternal() {
    info("Starting Soroban RPC payment observer");
    consecutiveFailedPolls = 0;
    chunkCursors.clear();
    if (executorService == null || executorService.isShutdown()) {
      executorService = Executors.newSingleThreadScheduledExecutor();
    }
    task =
        executorService.scheduleAtFixedRate(
            this::fetchEvents, 0, 1, java.util.concurrent.TimeUnit.SECONDS);
  }

  @Override
  void shutdownInternal() {
    task.cancel(true);
    executorService.shutdownNow();
  }

  @Override
  public String getName() {
    return "stellar_rpc_payment_observer";
  }

  @Override
  public List<Tags> getTags() {
    return List.of(ALL, EVENT);
  }

  @Override
  public HealthCheckResult check() {
    HealthCheckStatus status =
        switch (this.status) {
          case RUNNING -> GREEN;
          case STARTING -> YELLOW;
          default -> RED;
        };
    return SPOHealthCheckResult.builder().name(getName()).status(status).build();
  }

  /*****************************************
   * Mock Event Stream
   */
  String cursor = null;

  ScheduledExecutorService executorService = Executors.newSingleThreadScheduledExecutor();

  ScheduledFuture<?> task;

  /** The first wait, in seconds, before retrying after an event was held. */
  static final long HOLD_BACKOFF_INITIAL_SECONDS = 1;

  /**
   * The cap on the wait, in seconds. Fixed rather than taken from the event backoff settings on
   * purpose: those can be configured above the silence timeout, and a held event must stay retried
   * often enough for the observer to recover soon after the database is back.
   */
  static final long HOLD_BACKOFF_MAX_SECONDS = 30;

  /** Earliest time the next fetch may run, so a held event is not retried on every tick. */
  Instant retryNotBefore = Instant.EPOCH;

  long holdBackoffSeconds = HOLD_BACKOFF_INITIAL_SECONDS;

  void fetchEvents() {
    if (Instant.now().isBefore(retryNotBefore)) {
      // Waiting out a held event is not silence from the stream.
      lastActivityTime = Instant.now();
      return;
    }
    String cursor = (this.cursor != null) ? this.cursor : loadStellarRpcCursor();
    // Arm the silence detector at the first poll: a backend that never completes a poll must
    // still be able to trip it (checkSilence ignores a null lastActivityTime).
    if (lastActivityTime == null) {
      lastActivityTime = Instant.now();
    }

    try {
      List<GetEventsRequest> requests = buildEventRequests(cursor);

      boolean pollFailed = false;
      boolean pollRejected = false;
      boolean caughtUp = true;
      boolean canPersist = true;
      long latestLedger = 0;
      String nextCursor = null;

      for (int i = 0; i < requests.size(); i++) {
        // A request that does not complete holds its cursor at what it was sent.
        String sentCursor = chunkCursors.getOrDefault(i, cursor);
        String requestCursor = sentCursor;
        try {
          GetEventsResponse response = sorobanServer.getEvents(requests.get(i));
          latestLedger = response.getLatestLedger();
          String processed = processEvents(response.getEvents(), response.getCursor());
          if (processed != null) {
            requestCursor = processed;
          }
          if (processed == null || !processed.equals(response.getCursor())) {
            caughtUp = false;
          }
        } catch (SorobanRpcException rex) {
          // The RPC answered with an error: the request itself is rejected, so it will not heal.
          warnF("RPC rejected the events request: {}. ex={}", rex.toString(), rex.getMessage());
          pollRejected = true;
        } catch (NetworkException nex) {
          warnF("Network error in RPC observer loop: {}. ex={}", nex.toString(), nex.getMessage());
          pollFailed = true;
        }
        if (requestCursor == null) {
          canPersist = false;
        } else {
          chunkCursors.put(i, requestCursor);
          if (nextCursor == null || requestCursor.compareTo(nextCursor) < 0) {
            nextCursor = requestCursor;
          }
        }
      }

      if (pollRejected) {
        recordFailedPoll();
        caughtUp = false;
      } else if (pollFailed) {
        // An outage is waited out, as before: it neither counts as a rejection nor resets the
        // count.
        caughtUp = false;
      } else {
        consecutiveFailedPolls = 0;
        lastActivityTime = Instant.now();
        silenceTimeoutCount = 0;
        if (!requests.isEmpty()) {
          metricLatestBlockRead.set(latestLedger);
        }
      }

      if (canPersist && nextCursor != null) {
        try {
          if (!nextCursor.equals(cursor)) {
            saveCursor(nextCursor);
          }
          if (caughtUp) {
            streamBackoffTimer.reset();
            metricLatestBlockProcessed.set(latestLedger);
          }
        } catch (Exception tex) {
          warnF("Failed to persist RPC cursor. Will retry next tick. ex={}", tex.getMessage());
          setStatus(ObserverStatus.DATABASE_ERROR);
        }
      }
    } catch (IOException | NetworkException ex) {
      warnF(
          "Error building the RPC events request: {}. ex={}. Wait for next retry.",
          ex.toString(),
          ex.getMessage());
    } catch (Throwable t) {
      errorEx("Unhandled error in RPC observer loop", t);
      setStatus(ObserverStatus.STREAM_ERROR);
    }
  }

  private void recordFailedPoll() {
    if (++consecutiveFailedPolls >= RPC_REJECTION_LIMIT) {
      errorF(
          "The RPC rejected {} polls in a row. Setting status to STREAM_ERROR",
          consecutiveFailedPolls);
      setStatus(ObserverStatus.STREAM_ERROR);
    }
  }

  String processEvents(List<EventInfo> events, String responseCursor) {
    if (events == null || events.isEmpty()) {
      clearHold();
      return responseCursor;
    }
    debugF("Processing {} 'transfer' events", events.size());

    for (int i = 0; i < events.size(); i++) {
      EventInfo event = events.get(i);
      ShouldProcessResult result = shouldProcess(event);
      if (!result.shouldProcess) {
        continue;
      }
      try {
        processTransferEvent(result);
      } catch (IOException | NetworkException ex) {
        errorF(
            "Failed to process transfer event of transaction {}. It will be retried. ex={}",
            event.getTransactionHash(),
            ex.toString());
        retryNotBefore = Instant.now().plusSeconds(holdBackoffSeconds);
        holdBackoffSeconds = Math.min(holdBackoffSeconds * 2, HOLD_BACKOFF_MAX_SECONDS);
        return i == 0 ? null : events.get(i - 1).getId();
      } catch (Exception ex) {
        errorF(
            "Skipping transfer event of transaction {} that cannot be processed. ex={}",
            event.getTransactionHash(),
            ex.toString());
      }
    }
    clearHold();
    return responseCursor;
  }

  private void clearHold() {
    retryNotBefore = Instant.EPOCH;
    holdBackoffSeconds = HOLD_BACKOFF_INITIAL_SECONDS;
  }

  void processTransferEvent(ShouldProcessResult result) throws IOException, AnchorException {
    debug("Processing transfer event: {}", GsonUtils.getInstance().toJson(result.event));
    LedgerTransaction txn;
    try {
      txn =
          LedgerClientHelper.waitForTransactionAvailable(
              stellarRpc, result.event.getTransactionHash());
    } catch (LedgerDecodeException dex) {
      errorF(
          "Skipping transfer event of transaction {}: it cannot be decoded. ex={}",
          result.event.getTransactionHash(),
          dex.toString());
      Metrics.counter(AnchorMetrics.PAYMENT_OBSERVER_EVENT_SKIPPED.toString()).increment();
      return;
    } catch (LedgerException lex) {
      throw new IOException(
          "Transaction is not available: " + result.event.getTransactionHash(), lex);
    }
    int opIndex = result.event.getOperationIndex().intValue();
    String wantedOpId =
        String.valueOf(
            new TOID(txn.getSequenceNumber().intValue(), txn.getApplicationOrder(), opIndex + 1)
                .toInt64());
    LedgerOperation op =
        txn.getOperations().stream()
            .filter(o -> wantedOpId.equals(getOperationId(o)))
            .findFirst()
            .orElse(null);
    if (op == null && LedgerClientHelper.isInvokeHostFunctionOperation(txn, opIndex)) {
      txn = withInvokeHostFunctionOperation(txn, wantedOpId);
      op =
          txn.getOperations().stream()
              .filter(o -> wantedOpId.equals(getOperationId(o)))
              .findFirst()
              .orElse(null);
    }
    if (op == null) {
      errorF(
          "No creditable operation found for transfer event: txHash={}, operationIndex={}."
              + " Skipping.",
          result.event.getTransactionHash(),
          result.event.getOperationIndex());
      return;
    }
    processOperation(txn, op, result);
  }

  private String getOperationId(LedgerOperation op) {
    if (op.getPaymentOperation() != null) return op.getPaymentOperation().getId();
    if (op.getPathPaymentOperation() != null) return op.getPathPaymentOperation().getId();
    if (op.getInvokeHostFunctionOperation() != null)
      return op.getInvokeHostFunctionOperation().getId();
    return null;
  }

  @Builder
  @Getter
  static class ShouldProcessResult {
    EventInfo event;
    boolean shouldProcess;
    String fromAddr;
    String toAddr;
    String eventMemo;
    String sep11Asset;
    BigInteger amount;
  }

  private ShouldProcessResult shouldProcess(EventInfo event) {
    ShouldProcessResultBuilder builder = builder().event(event).shouldProcess(false);
    try {
      if (event.getTopic().size() != 4) {
        return builder.build();
      }

      SCVal function = SCVal.fromXdrBase64(event.getTopic().get(0));
      SCVal from = SCVal.fromXdrBase64(event.getTopic().get(1));
      SCVal to = SCVal.fromXdrBase64(event.getTopic().get(2));
      SCVal asset = SCVal.fromXdrBase64(event.getTopic().get(3));

      if (function.getDiscriminant() != SCValType.SCV_SYMBOL
          || !function.getSym().getSCSymbol().toString().equals("transfer")) {
        return builder.build();
      }

      if (from.getDiscriminant() != SCValType.SCV_ADDRESS
          || to.getDiscriminant() != SCValType.SCV_ADDRESS
          || asset.getDiscriminant() != SCValType.SCV_STRING) {
        return builder.build();
      }

      String fromAddr = Scv.fromAddress(from).toString();
      String toAddr = Scv.fromAddress(to).toString();
      BigInteger amount = BigInteger.ZERO;
      String eventMemo = null;
      SCVal scValue = SCVal.fromXdrBase64(event.getValue());
      // Reference:
      // https://github.com/stellar/stellar-protocol/blob/master/core/cap-0067.md#emit-a-map-as-the-data-field-in-the-transfer-and-mint-event-if-muxed-information-is-being-emitted-for-the-destination
      if (scValue.getDiscriminant() == SCValType.SCV_I128) {
        amount = Scv.fromInt128(scValue);
      } else if (scValue.getDiscriminant() == SCValType.SCV_MAP) {
        var entries = scValue.getMap() == null ? null : scValue.getMap().getSCMap();
        if (entries == null || entries.length < 2) {
          return builder.build();
        }
        SCVal amountVal = entries[0].getVal();
        SCVal memoVal = entries[1].getVal();
        if (amountVal.getDiscriminant() != SCValType.SCV_I128) {
          return builder.build();
        }
        amount = Scv.fromInt128(amountVal);
        eventMemo =
            switch (memoVal.getDiscriminant()) {
              case SCV_STRING -> memoVal.getStr().getSCString().toString();
              case SCV_U64 -> memoVal.getU64().toString();
              case SCV_BYTES ->
                  new String(Base64.getEncoder().encode(memoVal.getBytes().getSCBytes()));
              default -> null;
            };
        if (memoVal.getDiscriminant() == SCValType.SCV_U64) {
          try {
            toAddr =
                new MuxedAccount(Scv.fromAddress(to).toString(), Scv.fromUint64(memoVal))
                    .getAddress();
          } catch (IllegalArgumentException iae) {
            warnF(
                "Cannot build MuxedAccount for address '{}', using unmuxed value. ex={}",
                toAddr,
                iae.getMessage());
          }
        }
      }

      if (!paymentObservingAccountsManager.lookupAndUpdate(fromAddr)
          && !paymentObservingAccountsManager.lookupAndUpdate(toAddr)) {
        // If neither from nor to accounts are being observed, skip processing this event.
        return builder.build();
      }

      return builder
          .shouldProcess(true)
          .fromAddr(fromAddr)
          .toAddr(toAddr)
          .amount(amount)
          .eventMemo(eventMemo)
          .sep11Asset(asset.getStr().getSCString().toString())
          .build();
    } catch (Exception ex) {
      warnF(
          "Skip processing event: {}. ex={}", GsonUtils.getInstance().toJson(event), ex.toString());
      return builder.build();
    }
  }

  List<GetEventsRequest> buildEventRequests(String cursor) throws IOException {
    List<String> uniqueDistributionAccounts =
        assetService.getStellarAssets().stream()
            .filter(asset -> asset.getSchema() == AssetInfo.Schema.STELLAR)
            .map(StellarAssetInfo::getDistributionAccount)
            .distinct()
            .toList();

    List<EventFilter> filters =
        uniqueDistributionAccounts.stream()
            .flatMap(
                distributionAccount -> {
                  try {
                    return Stream.of(
                        // Filter for transfers from the distribution account
                        EventFilter.builder()
                            .type(EventFilterType.CONTRACT)
                            .topic(
                                List.of(
                                    Scv.toSymbol("transfer").toXdrBase64(),
                                    Scv.toAddress(distributionAccount).toXdrBase64(),
                                    "*",
                                    "*"))
                            .build(),
                        // Filter for transfers to the distribution account
                        EventFilter.builder()
                            .type(EventFilterType.CONTRACT)
                            .topic(
                                List.of(
                                    Scv.toSymbol("transfer").toXdrBase64(),
                                    "*",
                                    Scv.toAddress(distributionAccount).toXdrBase64(),
                                    "*"))
                            .build());
                  } catch (IOException e) {
                    Log.errorF(
                        "Skipping asset due to invalid distribution account: {}. Error: {}",
                        distributionAccount,
                        e.getMessage());
                    return Stream.empty();
                  }
                })
            .toList();

    if (filters.isEmpty()) {
      return List.of();
    }

    int chunks = (filters.size() + MAX_FILTERS_PER_REQUEST - 1) / MAX_FILTERS_PER_REQUEST;
    if (chunks != chunkCount) {
      // The chunk layout changed, so the indexes no longer name the same filters.
      chunkCursors.clear();
      chunkCount = chunks;
    }

    Long startLedger = null;
    List<GetEventsRequest> requests = new ArrayList<>();
    for (int i = 0; i < chunks; i++) {
      List<EventFilter> chunk =
          filters.subList(
              i * MAX_FILTERS_PER_REQUEST,
              Math.min((i + 1) * MAX_FILTERS_PER_REQUEST, filters.size()));
      String chunkCursor = chunkCursors.getOrDefault(i, cursor);
      if (isEmpty(chunkCursor)) {
        if (startLedger == null) {
          startLedger = Math.max(1, getLatestLedger() - 5);
        }
        requests.add(GetEventsRequest.builder().filters(chunk).startLedger(startLedger).build());
      } else {
        requests.add(
            GetEventsRequest.builder()
                .filters(chunk)
                .pagination(
                    GetEventsRequest.PaginationOptions.builder()
                        .limit(100L)
                        .cursor(chunkCursor)
                        .build())
                .build());
      }
    }
    return requests;
  }

  void processOperation(
      LedgerTransaction ledgerTxn, LedgerOperation op, ShouldProcessResult verified)
      throws IOException, AnchorException {
    PaymentTransferEvent event =
        switch (op.getType()) {
          case PAYMENT -> {
            LedgerPaymentOperation paymentOp = op.getPaymentOperation();
            yield PaymentTransferEvent.builder()
                .from(paymentOp.getFrom())
                .to(paymentOp.getTo())
                .sep11Asset(AssetHelper.getSep11AssetName(paymentOp.getAsset()))
                .amount(paymentOp.getAmount())
                .txHash(ledgerTxn.getHash())
                .operationId(paymentOp.getId())
                .ledgerTransaction(ledgerTxn)
                .build();
          }
          case PATH_PAYMENT_STRICT_SEND, PATH_PAYMENT_STRICT_RECEIVE -> {
            LedgerPathPaymentOperation pathPaymentOp = op.getPathPaymentOperation();
            yield PaymentTransferEvent.builder()
                .from(pathPaymentOp.getFrom())
                .to(pathPaymentOp.getTo())
                .sep11Asset(AssetHelper.getSep11AssetName(pathPaymentOp.getAsset()))
                .amount(pathPaymentOp.getAmount())
                .txHash(ledgerTxn.getHash())
                .operationId(pathPaymentOp.getId())
                .ledgerTransaction(ledgerTxn)
                .build();
          }
          case INVOKE_HOST_FUNCTION -> {
            LedgerTransaction.LedgerInvokeHostFunctionOperation invokeOp =
                op.getInvokeHostFunctionOperation();
            String emittingContractId = verified.event.getContractId();
            Asset asset = sacToAssetMapper.getAssetFromSac(emittingContractId);
            if (asset == null) {
              warnF(
                  "Event-emitting contract {} is not a Stellar Asset Contract. Skipping operation {}.",
                  emittingContractId,
                  invokeOp.getId());
              yield null;
            }
            invokeOp.setAsset(asset);
            yield PaymentTransferEvent.builder()
                .from(verified.fromAddr)
                .to(verified.toAddr)
                .sep11Asset(AssetHelper.getSep11AssetName(invokeOp.getAsset()))
                .amount(verified.amount)
                .txHash(ledgerTxn.getHash())
                .operationId(invokeOp.getId())
                .ledgerTransaction(ledgerTxn)
                .build();
          }
          default -> null;
        };
    if (event != null) {
      handleEvent(event);
    }
  }

  private void saveCursor(String cursor) {
    this.cursor = cursor;
    saveStellarRpcCursor(cursor);
  }

  private Long getLatestLedger() {
    GetLatestLedgerResponse response = sorobanServer.getLatestLedger();
    return response.getSequence().longValue();
  }
}
