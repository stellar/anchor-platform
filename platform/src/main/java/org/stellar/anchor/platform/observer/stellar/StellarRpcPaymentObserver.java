package org.stellar.anchor.platform.observer.stellar;

import static org.stellar.anchor.api.platform.HealthCheckStatus.GREEN;
import static org.stellar.anchor.healthcheck.HealthCheckable.Tags.ALL;
import static org.stellar.anchor.healthcheck.HealthCheckable.Tags.EVENT;
import static org.stellar.anchor.platform.observer.stellar.StellarRpcPaymentObserver.ShouldProcessResult.*;
import static org.stellar.anchor.util.Log.*;
import static org.stellar.anchor.util.StringHelper.isEmpty;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import lombok.Builder;
import lombok.Getter;
import org.stellar.anchor.api.exception.AnchorException;
import org.stellar.anchor.api.platform.HealthCheckResult;
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
import org.stellar.sdk.MuxedAccount;
import org.stellar.sdk.SorobanServer;
import org.stellar.sdk.requests.sorobanrpc.EventFilterType;
import org.stellar.sdk.requests.sorobanrpc.GetEventsRequest;
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

  public StellarRpcPaymentObserver(
      String rpcUrl,
      StellarPaymentObserverConfig config,
      List<PaymentListener> paymentListeners,
      PaymentObservingAccountsManager paymentObservingAccountsManager,
      StellarPaymentStreamerCursorStore paymentStreamerCursorStore,
      SacToAssetMapper sacToAssetMapper) {
    super(config, paymentListeners, paymentObservingAccountsManager, paymentStreamerCursorStore);
    this.stellarRpc = new StellarRpc(rpcUrl);
    this.sorobanServer = stellarRpc.getSorobanServer();
    this.sacToAssetMapper = sacToAssetMapper;
  }

  @Override
  void startInternal() {
    info("Starting Soroban RPC payment observer");
    task =
        executorService.scheduleAtFixedRate(
            this::fetchEvents, 0, 1, java.util.concurrent.TimeUnit.SECONDS);
  }

  @Override
  void shutdownInternal() {
    task.cancel(true);
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
    // TODO: Implement health check for Stellar RPC when futurenet unified event is available
    return SPOHealthCheckResult.builder().name(getName()).status(GREEN).build();
  }

  /*****************************************
   * Mock Event Stream
   */
  String cursor = null;

  ScheduledExecutorService executorService = Executors.newSingleThreadScheduledExecutor();

  ScheduledFuture<?> task;

  private void fetchEvents() {
    String cursor = (this.cursor != null) ? this.cursor : loadCursorFromDatabase();

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
    for (EventInfo event : events) {
      ShouldProcessResult result = shouldProcess(event);
      if (result.shouldProcess) {
        processTransferEvent(result);
      }
    }
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
      SCVal scValue = SCVal.fromXdrBase64(event.getValue());
      // Reference:
      // https://github.com/stellar/stellar-protocol/blob/master/core/cap-0067.md#emit-a-map-as-the-data-field-in-the-transfer-and-mint-event-if-muxed-information-is-being-emitted-for-the-destination
      if (scValue.getDiscriminant() == SCValType.SCV_I128) {
        amount = Scv.fromInt128(scValue).longValue();
      } else if (scValue.getDiscriminant() == SCValType.SCV_MAP) {
        amount = Scv.fromInt128(scValue.getMap().getSCMap()[0].getVal()).longValue();
        if (scValue.getMap().getSCMap()[1].getVal().getDiscriminant() == SCValType.SCV_U64) {
          // In case the MEMO_ID is present, convert the toAddr to MuxedAccount.
          // In the cases of the transaction memo being MEMO_TEXT or MEMO_HASH, the toAddr is not
          // muxed
          builder.toAddr(
              new MuxedAccount(
                      Scv.fromAddress(to).toString(),
                      Scv.fromUint64(scValue.getMap().getSCMap()[1].getVal()))
                  .getAddress());
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

  private GetEventsRequest buildEventRequest(String cursor) throws IOException {
    GetEventsRequest.EventFilter filter =
        GetEventsRequest.EventFilter.builder()
            .type(EventFilterType.CONTRACT)
            .topic(List.of(Scv.toSymbol("transfer").toXdrBase64(), "**"))
            .build();

    if (isEmpty(cursor)) {
      long latestLedger = getLatestLedger();
      return GetEventsRequest.builder()
          .filters(List.of(filter))
          .startLedger(latestLedger - 1)
          .build();
    } else {
      return GetEventsRequest.builder()
          .filters(List.of(filter))
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
    saveCursorToDatabase(cursor);
  }

  private Long getLatestLedger() {
    GetLatestLedgerResponse response = sorobanServer.getLatestLedger();
    return response.getSequence().longValue();
  }
}
