package org.stellar.anchor.platform.observer.stellar;

import static org.stellar.anchor.api.platform.HealthCheckStatus.*;
import static org.stellar.anchor.api.platform.HealthCheckStatus.RED;
import static org.stellar.anchor.healthcheck.HealthCheckable.Tags.ALL;
import static org.stellar.anchor.healthcheck.HealthCheckable.Tags.EVENT;
import static org.stellar.anchor.platform.observer.stellar.StellarRpcPaymentObserver.ShouldProcessResult.*;
import static org.stellar.anchor.util.Log.*;
import static org.stellar.anchor.util.StringHelper.isEmpty;

import java.io.IOException;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.stream.Stream;
import lombok.Builder;
import lombok.Getter;
import lombok.val;
import org.stellar.anchor.api.asset.AssetInfo;
import org.stellar.anchor.api.asset.StellarAssetInfo;
import org.stellar.anchor.api.exception.AnchorException;
import org.stellar.anchor.api.platform.HealthCheckResult;
import org.stellar.anchor.api.platform.HealthCheckStatus;
import org.stellar.anchor.asset.AssetService;
import org.stellar.anchor.ledger.LedgerTransaction;
import org.stellar.anchor.ledger.LedgerTransaction.LedgerOperation;
import org.stellar.anchor.ledger.LedgerTransaction.LedgerPathPaymentOperation;
import org.stellar.anchor.ledger.LedgerTransaction.LedgerPaymentOperation;
import org.stellar.anchor.ledger.PaymentTransferEvent;
import org.stellar.anchor.ledger.StellarRpc;
import org.stellar.anchor.platform.config.PaymentObserverConfig.StellarPaymentObserverConfig;
import org.stellar.anchor.platform.observer.PaymentListener;
import org.stellar.anchor.util.AssetHelper;
import org.stellar.anchor.util.GsonUtils;
import org.stellar.anchor.util.Log;
import org.stellar.sdk.MuxedAccount;
import org.stellar.sdk.SorobanServer;
import org.stellar.sdk.requests.sorobanrpc.EventFilterType;
import org.stellar.sdk.requests.sorobanrpc.GetEventsRequest;
import org.stellar.sdk.requests.sorobanrpc.GetEventsRequest.EventFilter;
import org.stellar.sdk.responses.sorobanrpc.GetEventsResponse;
import org.stellar.sdk.responses.sorobanrpc.GetEventsResponse.EventInfo;
import org.stellar.sdk.responses.sorobanrpc.GetLatestLedgerResponse;
import org.stellar.sdk.scval.Scv;
import org.stellar.sdk.xdr.SCVal;
import org.stellar.sdk.xdr.SCValType;

public class StellarRpcPaymentObserver extends AbstractPaymentObserver {
  @Getter final StellarRpc stellarRpc;
  @Getter final SorobanServer sorobanServer;
  final SacToAssetMapper sacToAssetMapper;
  final AssetService assetService;
  ObserverStatus status = ObserverStatus.STARTING;

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
  }

  @Override
  void startInternal() {
    info("Starting Soroban RPC payment observer");
    task =
        executorService.scheduleAtFixedRate(
            this::fetchEvents, 0, 1, java.util.concurrent.TimeUnit.SECONDS);
    status = ObserverStatus.RUNNING;
  }

  @Override
  void shutdownInternal() {
    task.cancel(true);
    status = ObserverStatus.SHUTDOWN;
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

  private void fetchEvents() {
    String cursor = (this.cursor != null) ? this.cursor : loadStellarRpcCursor();

    try {
      GetEventsResponse response = sorobanServer.getEvents(buildEventRequest(cursor));
      if (response.getEvents() != null && !response.getEvents().isEmpty()) {
        processEvents(response.getEvents());
      }
      // Save the cursor for the next request
      cursor = response.getCursor();
      saveCursor(cursor);
    } catch (IOException ioex) {
      warnF(
          "Error fetching latest ledger: {}. ex={}. Wait for next retry.",
          GsonUtils.getInstance().toJson(ioex),
          ioex.getMessage());
    }
  }

  private void processEvents(List<EventInfo> events) {
    if (events == null || events.isEmpty()) return;
    debugF("Processing {} 'transfer' events", events.size());
    val lastEvent = events.get(events.size() - 1);
    if (lastEvent != null) metricLatestBlockRead.set(lastEvent.getLedger());

    for (EventInfo event : events) {
      ShouldProcessResult result = shouldProcess(event);
      if (result.shouldProcess) {
        processTransferEvent(result);
      }
    }

    if (lastEvent != null) metricLatestBlockProcessed.set(lastEvent.getLedger());
  }

  private void processTransferEvent(ShouldProcessResult result) {
    debug("Processing transfer event: {}", GsonUtils.getInstance().toJson(result.event));
    try {
      LedgerTransaction txn = stellarRpc.getTransaction(result.event.getTransactionHash());
      LedgerOperation op = txn.getOperations().get(result.event.getOperationIndex().intValue());
      processOperation(txn, op);
    } catch (Exception ex) {
      warnF(
          "Error processing transfer event: {}. ex={}",
          GsonUtils.getInstance().toJson(result.event),
          ex.getMessage());
    }
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
    Long amount;
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
      long amount = 0L;
      String eventMemo = null;
      SCVal scValue = SCVal.fromXdrBase64(event.getValue());
      // Reference:
      // https://github.com/stellar/stellar-protocol/blob/master/core/cap-0067.md#emit-a-map-as-the-data-field-in-the-transfer-and-mint-event-if-muxed-information-is-being-emitted-for-the-destination
      if (scValue.getDiscriminant() == SCValType.SCV_I128) {
        amount = Scv.fromInt128(scValue).longValue();
      } else if (scValue.getDiscriminant() == SCValType.SCV_MAP) {
        amount = Scv.fromInt128(scValue.getMap().getSCMap()[0].getVal()).longValue();
        SCVal memoVal = scValue.getMap().getSCMap()[1].getVal();
        eventMemo =
            switch (memoVal.getDiscriminant()) {
              case SCV_STRING -> memoVal.getStr().getSCString().toString();
              case SCV_U64 -> memoVal.getU64().toString();
              case SCV_BYTES ->
                  new String(Base64.getEncoder().encode(memoVal.getBytes().getSCBytes()));
              default -> null;
            };
        if (scValue.getMap().getSCMap()[1].getVal().getDiscriminant() == SCValType.SCV_U64) {
          toAddr =
              new MuxedAccount(
                      Scv.fromAddress(to).toString(),
                      Scv.fromUint64(scValue.getMap().getSCMap()[1].getVal()))
                  .getAddress();
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
    } catch (IOException ioex) {
      warnF(
          "Skip processing event: {}. ex={}",
          GsonUtils.getInstance().toJson(event),
          ioex.getMessage());
      return builder.build();
    }
  }

  GetEventsRequest buildEventRequest(String cursor) throws IOException {
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

    if (isEmpty(cursor)) {
      long latestLedger = getLatestLedger();
      return GetEventsRequest.builder().filters(filters).startLedger(latestLedger - 1).build();
    } else {
      return GetEventsRequest.builder()
          .filters(filters)
          .pagination(
              GetEventsRequest.PaginationOptions.builder().limit(100L).cursor(cursor).build())
          .build();
    }
  }

  void processOperation(LedgerTransaction ledgerTxn, LedgerOperation op)
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
            invokeOp.setAsset(sacToAssetMapper.getAssetFromSac(invokeOp.getContractId()));
            yield PaymentTransferEvent.builder()
                .from(invokeOp.getFrom())
                .to(invokeOp.getTo())
                .sep11Asset(AssetHelper.getSep11AssetName(invokeOp.getAsset()))
                .amount(invokeOp.getAmount())
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
