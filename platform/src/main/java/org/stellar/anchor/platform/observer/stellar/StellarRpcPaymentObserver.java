package org.stellar.anchor.platform.observer.stellar;

import static org.stellar.anchor.api.platform.HealthCheckStatus.GREEN;
import static org.stellar.anchor.healthcheck.HealthCheckable.Tags.ALL;
import static org.stellar.anchor.healthcheck.HealthCheckable.Tags.EVENT;
import static org.stellar.anchor.util.Log.*;

import java.io.IOException;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import lombok.Getter;
import org.stellar.anchor.api.exception.AnchorException;
import org.stellar.anchor.api.exception.LedgerException;
import org.stellar.anchor.api.platform.HealthCheckResult;
import org.stellar.anchor.ledger.LedgerClientHelper;
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
import org.stellar.sdk.SorobanServer;
import org.stellar.sdk.requests.sorobanrpc.GetTransactionsRequest;
import org.stellar.sdk.responses.sorobanrpc.GetLatestLedgerResponse;
import org.stellar.sdk.responses.sorobanrpc.GetTransactionsResponse;
import org.stellar.sdk.xdr.OperationType;
import org.stellar.sdk.xdr.TransactionEnvelope;

public class StellarRpcPaymentObserver extends AbstractPaymentObserver {
  @Getter final StellarRpc stellarRpc;
  @Getter final SorobanServer sorobanServer;
  final SacToAssetMapper sacToAssetMapper;

  public StellarRpcPaymentObserver(
      String rpcUrl,
      StellarPaymentObserverConfig config,
      List<PaymentListener> paymentListeners,
      PaymentObservingAccountsManager paymentObservingAccountsManager,
      StellarPaymentStreamerCursorStore paymentStreamerCursorStore) {
    super(config, paymentListeners, paymentObservingAccountsManager, paymentStreamerCursorStore);
    this.stellarRpc = new StellarRpc(rpcUrl);
    this.sorobanServer = stellarRpc.getSorobanServer();
    this.sacToAssetMapper = new SacToAssetMapper(this.sorobanServer);
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
          LedgerTransaction ledgerTxn = fromStellarRpcTransaction(txn);
          if (ledgerTxn == null) {
            continue;
          }
          ledgerTxn
              .getOperations()
              .forEach(
                  op -> {
                    try {
                      if (shouldProcess(op)) {
                        processOperation(ledgerTxn, op);
                      }
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

  /**
   * Check if the payment observer should process the operation. This is used to filter out unwanted
   * operations from StellarRPC
   *
   * @param operation the operation to check
   * @return true if the operation should be processed, false otherwise
   */
  boolean shouldProcess(LedgerOperation operation) {
    if (!EnumSet.of(
            OperationType.PAYMENT,
            OperationType.PATH_PAYMENT_STRICT_SEND,
            OperationType.PATH_PAYMENT_STRICT_RECEIVE,
            OperationType.INVOKE_HOST_FUNCTION)
        .contains(operation.getType())) {
      return false;
    }
    return switch (operation.getType()) {
      case PAYMENT -> {
        LedgerTransaction.LedgerPaymentOperation paymentOp = operation.getPaymentOperation();
        yield paymentObservingAccountsManager.lookupAndUpdate(paymentOp.getFrom())
            || paymentObservingAccountsManager.lookupAndUpdate(paymentOp.getTo());
      }
      case PATH_PAYMENT_STRICT_SEND, PATH_PAYMENT_STRICT_RECEIVE -> {
        LedgerTransaction.LedgerPathPaymentOperation pathPaymentOp =
            operation.getPathPaymentOperation();
        yield paymentObservingAccountsManager.lookupAndUpdate(pathPaymentOp.getFrom())
            || paymentObservingAccountsManager.lookupAndUpdate(pathPaymentOp.getTo());
      }
      case INVOKE_HOST_FUNCTION -> {
        LedgerTransaction.LedgerInvokeHostFunctionOperation invokeOp =
            operation.getInvokeHostFunctionOperation();
        debug(
            "Received invoke host function operation: {}",
            GsonUtils.getInstance().toJson(invokeOp));
        yield paymentObservingAccountsManager.lookupAndUpdate(invokeOp.getFrom())
            || paymentObservingAccountsManager.lookupAndUpdate(invokeOp.getTo());
      }
      default -> false;
    };
  }

  private void processOperation(LedgerTransaction ledgerTxn, LedgerOperation op)
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
            invokeOp.setAsset(
                sacToAssetMapper.getAssetFromSac(invokeOp.getStellarAssetContractId()));
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

  /**
   * Converting from Stellar RPC transaction to LedgerTransaction. TODO: This function will be
   * removed after migrating to getEvents methods.
   *
   * @param txn the Stellar RPC transaction to convert
   * @return the converted LedgerTransaction
   * @throws LedgerException if the transaction is null or malformed
   */
  LedgerTransaction fromStellarRpcTransaction(GetTransactionsResponse.Transaction txn)
      throws LedgerException {
    TransactionEnvelope txnEnv;
    try {
      txnEnv = TransactionEnvelope.fromXdrBase64(txn.getEnvelopeXdr());
    } catch (IOException ioex) {
      throw new LedgerException("Unable to parse transaction envelope", ioex);
    }
    Integer applicationOrder = txn.getApplicationOrder();
    Long sequenceNumber = txn.getLedger();
    LedgerClientHelper.ParseResult parseResult =
        LedgerClientHelper.parseOperationAndSourceAccountAndMemo(txnEnv, txn.getTxHash());
    if (parseResult == null) return null;
    List<LedgerTransaction.LedgerOperation> operations =
        LedgerClientHelper.getLedgerOperations(applicationOrder, sequenceNumber, parseResult);
    return LedgerTransaction.builder()
        .hash(txn.getTxHash())
        .ledger(txn.getLedger())
        .applicationOrder(txn.getApplicationOrder())
        .sourceAccount(parseResult.sourceAccount())
        .envelopeXdr(txn.getEnvelopeXdr())
        .memo(parseResult.memo())
        .sequenceNumber(sequenceNumber)
        .createdAt(Instant.ofEpochSecond(txn.getCreatedAt()))
        .operations(operations)
        .build();
  }
}
