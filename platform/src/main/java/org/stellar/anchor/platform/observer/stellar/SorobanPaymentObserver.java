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
import org.stellar.sdk.xdr.TransactionEnvelope;

public class SorobanPaymentObserver extends AbstractPaymentObserver {
  final StellarRpc stellarRpc;
  final SorobanServer sorobanServer;

  SorobanPaymentObserver(
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
    info("Starting Stellar RPC payment observer");
    startMockStream();
  }

  @Override
  void shutdownInternal() {
    shutdownMockStream();
  }

  @Override
  public String getName() {
    return "rpc_payment_observer";
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
          TransactionEnvelope txnEnv = TransactionEnvelope.fromXdrBase64(txn.getEnvelopeXdr());
          LedgerClientHelper.ParseResult parseResult =
              LedgerClientHelper.parseOperationAndSourceAccountAndMemo(txnEnv, txn.getTxHash());
          List<LedgerTransaction.LedgerOperation> operations = null;

          switch (txnEnv.getDiscriminant()) {
            case ENVELOPE_TYPE_TX_V0:
              operations =
                  LedgerClientHelper.getLedgerOperations(
                      txn.getApplicationOrder(),
                      txnEnv.getV0().getTx().getSeqNum().getSequenceNumber().getInt64(),
                      parseResult);
              break;
            case ENVELOPE_TYPE_TX:
              operations =
                  LedgerClientHelper.getLedgerOperations(
                      txn.getApplicationOrder(),
                      txnEnv.getV1().getTx().getSeqNum().getSequenceNumber().getInt64(),
                      parseResult);
              break;
            default:
              break;
          }
          assert operations != null;
          operations.forEach(
              op -> {
                try {
                  processOperation(txn.getTxHash(), op);
                } catch (IOException | AnchorException e) {
                  warnF(
                      "Skipping a received operation. Error processing operation: {}. ex={}",
                      GsonUtils.getInstance().toJson(op),
                      e.getMessage());
                }
              });
        } catch (IOException e) {
          debugF(
              "Unable to parse transaction envelope. hash={}. ex={}",
              txn.getTxHash(),
              e.getMessage());
          System.out.println("Unable to parse transaction envelope. hash=" + txn.getTxHash());
        } catch (LedgerException lex) {
          debugF("Error getting transaction: {}. ex={}", txn.getTxHash(), lex.getMessage());
        }
      }
      saveCursor(response.getCursor());
    }
  }

  private void processOperation(String txHash, LedgerTransaction.LedgerOperation op)
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
                .txHash(txHash)
                .operationId(paymentOp.getId())
                .build();
          }
          case PATH_PAYMENT_STRICT_SEND, PATH_PAYMENT_STRICT_RECEIVE -> {
            LedgerPathPaymentOperation paymentOp = op.getPathPaymentOperation();
            yield PaymentTransferEvent.builder()
                .from(paymentOp.getFrom())
                .to(paymentOp.getTo())
                .sep11Asset(AssetHelper.getSep11AssetName(op.getPaymentOperation().getAsset()))
                .amount(paymentOp.getAmount())
                .txHash(txHash)
                .operationId(paymentOp.getId())
                .build();
          }
          default -> null;
        };
    if (event != null) {
      handleEvent(event);
    }
  }

  private String fetchCursor() {
    // TODO: Implement cursor fetching logic
    return cursor;
  }

  private void saveCursor(String cursor) {
    // TODO: Implement cursor saving logic
    this.cursor = cursor;
  }

  private Long getLatestLedger() {
    GetLatestLedgerResponse response = sorobanServer.getLatestLedger();
    return response.getSequence().longValue();
  }
}
