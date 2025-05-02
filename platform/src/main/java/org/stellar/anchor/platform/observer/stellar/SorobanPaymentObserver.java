package org.stellar.anchor.platform.observer.stellar;

import static org.stellar.anchor.api.platform.HealthCheckStatus.GREEN;
import static org.stellar.anchor.healthcheck.HealthCheckable.Tags.ALL;
import static org.stellar.anchor.healthcheck.HealthCheckable.Tags.EVENT;
import static org.stellar.anchor.util.Log.*;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import org.stellar.anchor.api.exception.AnchorException;
import org.stellar.anchor.api.exception.LedgerException;
import org.stellar.anchor.api.platform.HealthCheckResult;
import org.stellar.anchor.ledger.LedgerClientHelper;
import org.stellar.anchor.ledger.LedgerTransaction;
import org.stellar.anchor.ledger.LedgerTransaction.LedgerPathPaymentOperation;
import org.stellar.anchor.ledger.LedgerTransaction.LedgerPaymentOperation;
import org.stellar.anchor.ledger.PaymentTransferEvent;
import org.stellar.anchor.ledger.StellarRpc;
import org.stellar.anchor.platform.config.PaymentObserverConfig.StellarPaymentObserverConfig;
import org.stellar.anchor.platform.observer.PaymentListener;
import org.stellar.anchor.util.AssetHelper;
import org.stellar.anchor.util.GsonUtils;
import org.stellar.sdk.SorobanServer;
import org.stellar.sdk.requests.sorobanrpc.GetTransactionsRequest;
import org.stellar.sdk.responses.sorobanrpc.GetLatestLedgerResponse;
import org.stellar.sdk.responses.sorobanrpc.GetTransactionsResponse;

public class SorobanPaymentObserver extends AbstractPaymentObserver {
  final StellarRpc stellarRpc;
  final SorobanServer sorobanServer;

  public SorobanPaymentObserver(
      String rpcUrl,
      StellarPaymentObserverConfig config,
      List<PaymentListener> paymentListeners,
      PaymentObservingAccountsManager paymentObservingAccountsManager,
      StellarPaymentStreamerCursorStore paymentStreamerCursorStore) {
    super(config, paymentListeners, paymentObservingAccountsManager, paymentStreamerCursorStore);
    this.stellarRpc = new StellarRpc(rpcUrl);
    this.sorobanServer = stellarRpc.getSorobanServer();
  }

  @Override
  void startInternal() {
    info("Starting Soroban RPC payment observer");
    startMockStream();
  }

  @Override
  void shutdownInternal() {
    shutdownMockStream();
  }

  @Override
  public String getName() {
    return "soroban_payment_observer";
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

  void startMockStream() {
    cursor = fetchCursor();
    task =
        executorService.scheduleAtFixedRate(
            this::fetchEvent, 0, 1, java.util.concurrent.TimeUnit.SECONDS);
  }

  void shutdownMockStream() {
    task.cancel(true);
  }

  private void fetchEvent() {
    String cursor = fetchCursor();
    GetTransactionsRequest request;
    if (cursor == null) {
      request = GetTransactionsRequest.builder().startLedger(getLatestLedger()).build();
    } else {
      request =
          GetTransactionsRequest.builder()
              .pagination(GetTransactionsRequest.PaginationOptions.builder().cursor(cursor).build())
              .build();
    }
    GetTransactionsResponse response = sorobanServer.getTransactions(request);
    if (response.getTransactions() != null) {
      debugF("Fetched {} transactions", response.getTransactions().size());
      for (GetTransactionsResponse.Transaction txn : response.getTransactions()) {
        // Process the transaction
        try {
          LedgerTransaction ledgerTxn = LedgerClientHelper.fromSorobantransaction(txn);
          if (ledgerTxn == null) {
            continue;
          }
          ledgerTxn
              .getOperations()
              .forEach(
                  op -> {
                    try {
                      processOperation(ledgerTxn, op);
                    } catch (IOException | AnchorException e) {
                      warnF(
                          "Skipping a received operation. Error processing operation: {}. ex={}",
                          GsonUtils.getInstance().toJson(op),
                          e.getMessage());
                    }
                  });
        } catch (LedgerException lex) {
          debugF("Error getting transaction: {}. ex={}", txn.getTxHash(), lex.getMessage());
        }
      }
      saveCursor(response.getCursor());
    }
  }

  private void processOperation(LedgerTransaction ledgerTxn, LedgerTransaction.LedgerOperation op)
      throws IOException, AnchorException {
    PaymentTransferEvent event =
        switch (op.getType()) {
          case PAYMENT -> {
            LedgerPaymentOperation paymentOp = op.getPaymentOperation();
            yield PaymentTransferEvent.builder()
                .from(paymentOp.getFrom())
                .to(paymentOp.getTo())
                .sep11Asset(AssetHelper.getSep11AssetName(op.getPaymentOperation().getAsset()))
                .amount(paymentOp.getAmount())
                .txHash(ledgerTxn.getHash())
                .operationId(paymentOp.getId())
                .ledgerTransaction(ledgerTxn)
                .build();
          }
          case PATH_PAYMENT_STRICT_SEND, PATH_PAYMENT_STRICT_RECEIVE -> {
            LedgerPathPaymentOperation paymentOp = op.getPathPaymentOperation();
            yield PaymentTransferEvent.builder()
                .from(paymentOp.getFrom())
                .to(paymentOp.getTo())
                .sep11Asset(AssetHelper.getSep11AssetName(op.getPaymentOperation().getAsset()))
                .amount(paymentOp.getAmount())
                .txHash(ledgerTxn.getHash())
                .operationId(paymentOp.getId())
                .ledgerTransaction(ledgerTxn)
                .build();
          }
          default -> null;
        };
    if (event != null) {
      handleEvent(event);
    }
  }

  private String fetchCursor() {
    // TODO: Implement cursor fetching logic when unified event is available
    return cursor;
  }

  private void saveCursor(String cursor) {
    // TODO: Implement cursor saving logic when unified event is available
    this.cursor = cursor;
  }

  private Long getLatestLedger() {
    GetLatestLedgerResponse response = sorobanServer.getLatestLedger();
    return response.getSequence().longValue();
  }
}
