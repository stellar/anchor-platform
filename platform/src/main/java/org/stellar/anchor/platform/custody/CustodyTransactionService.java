package org.stellar.anchor.platform.custody;

import static org.stellar.anchor.api.platform.PlatformTransactionData.Kind.DEPOSIT;
import static org.stellar.anchor.api.platform.PlatformTransactionData.Kind.RECEIVE;
import static org.stellar.anchor.api.platform.PlatformTransactionData.Kind.WITHDRAWAL;
import static org.stellar.anchor.platform.data.CustodyTransactionStatus.CREATED;
import static org.stellar.anchor.platform.data.CustodyTransactionStatus.SUBMITTED;
import static org.stellar.anchor.util.Log.debugF;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.apache.commons.lang3.StringUtils;
import org.springframework.http.HttpStatus;
import org.stellar.anchor.api.custody.CreateCustodyTransactionRequest;
import org.stellar.anchor.api.custody.CreateTransactionPaymentResponse;
import org.stellar.anchor.api.custody.CreateTransactionRefundRequest;
import org.stellar.anchor.api.exception.AnchorException;
import org.stellar.anchor.api.exception.FireblocksException;
import org.stellar.anchor.api.exception.custody.CustodyBadRequestException;
import org.stellar.anchor.api.exception.custody.CustodyNotFoundException;
import org.stellar.anchor.api.exception.custody.CustodyServiceUnavailableException;
import org.stellar.anchor.api.exception.custody.CustodyTooManyRequestsException;
import org.stellar.anchor.platform.data.CustodyTransactionStatus;
import org.stellar.anchor.platform.data.JdbcCustodyTransaction;
import org.stellar.anchor.platform.data.JdbcCustodyTransactionRepo;

public abstract class CustodyTransactionService {

  private final CustodyPaymentService<?> custodyPaymentService;
  private final JdbcCustodyTransactionRepo custodyTransactionRepo;

  public CustodyTransactionService(
      JdbcCustodyTransactionRepo custodyTransactionRepo,
      CustodyPaymentService<?> custodyPaymentService) {
    this.custodyTransactionRepo = custodyTransactionRepo;
    this.custodyPaymentService = custodyPaymentService;
  }

  protected abstract void validateRequest(CreateCustodyTransactionRequest request)
      throws CustodyBadRequestException;

  /**
   * Create custody transaction
   *
   * @param request custody transaction info
   * @return {@link JdbcCustodyTransaction} object
   */
  public JdbcCustodyTransaction create(CreateCustodyTransactionRequest request)
      throws CustodyBadRequestException {
    validateRequest(request);

    return custodyTransactionRepo.save(
        JdbcCustodyTransaction.builder()
            .id(UUID.randomUUID().toString())
            .sepTxId(request.getId())
            .status(CustodyTransactionStatus.CREATED.toString())
            .createdAt(Instant.now())
            .memo(request.getMemo())
            .memoType(request.getMemoType())
            .protocol(request.getProtocol())
            .fromAccount(request.getFromAccount())
            .toAccount(request.getToAccount())
            .amount(request.getAmount())
            .amountAsset(request.getAmountAsset())
            .kind(request.getKind())
            .reconciliationAttemptCount(0)
            .build());
  }

  /**
   * Create custody transaction payment. This method acts like a proxy. It forwards request to
   * custody payment service, update custody transaction and handles errors
   *
   * @param txnId custody/SEP transaction ID
   * @param requestBody additional data, that will be sent in a request to custody service. Can be
   *     used, if, for example, custody service supports some fields in a request specific to it
   * @return external transaction payment ID
   */
  public CreateTransactionPaymentResponse createPayment(String txnId, String requestBody)
      throws AnchorException {
    JdbcCustodyTransaction txn = custodyTransactionRepo.findBySepTxId(txnId).orElse(null);
    if (txn == null) {
      throw new CustodyNotFoundException(String.format("Transaction (id=%s) is not found", txnId));
    }

    CreateTransactionPaymentResponse response;
    try {
      response = custodyPaymentService.createTransactionPayment(txn, requestBody);
      updateCustodyTransaction(txn, response.getId(), SUBMITTED);
    } catch (FireblocksException e) {
      updateCustodyTransaction(txn, StringUtils.EMPTY, CustodyTransactionStatus.FAILED);
      throw (getResponseException(e));
    }

    return response;
  }

  /**
   * Create custody transaction refund. This method acts like a proxy. It forwards request to
   * custody payment service, updates custody transaction and handles errors
   *
   * @param txnId custody/SEP transaction ID
   * @param refundRequest {@link CreateTransactionRefundRequest} object
   * @return external transaction payment ID
   */
  public CreateTransactionPaymentResponse createRefund(
      String txnId, CreateTransactionRefundRequest refundRequest) throws AnchorException {
    JdbcCustodyTransaction txn = custodyTransactionRepo.findBySepTxId(txnId).orElse(null);
    if (txn == null) {
      throw new CustodyNotFoundException(String.format("Transaction (id=%s) is not found", txnId));
    }

    JdbcCustodyTransaction refundTxn = createTransactionRefundRecord(txn, refundRequest);

    CreateTransactionPaymentResponse response;
    try {
      response = custodyPaymentService.createTransactionRefund(refundTxn);
      updateCustodyTransaction(refundTxn, response.getId(), SUBMITTED);
    } catch (FireblocksException e) {
      updateCustodyTransaction(txn, StringUtils.EMPTY, CustodyTransactionStatus.FAILED);
      throw (getResponseException(e));
    }

    return response;
  }

  private JdbcCustodyTransaction createTransactionRefundRecord(
      JdbcCustodyTransaction txn, CreateTransactionRefundRequest refundRequest)
      throws CustodyBadRequestException {
    return create(
        CreateCustodyTransactionRequest.builder()
            .id(txn.getSepTxId())
            .memo(refundRequest.getMemo())
            .memoType(refundRequest.getMemoType())
            .protocol(txn.getProtocol())
            .toAccount(txn.getFromAccount())
            .amount(refundRequest.getAmount())
            .amountAsset(txn.getAmountAsset())
            .kind(DEPOSIT.getKind())
            .build());
  }

  private void updateCustodyTransaction(
      JdbcCustodyTransaction txn, String externalTransactionId, CustodyTransactionStatus status) {
    txn.setExternalTxId(externalTransactionId);
    txn.setStatus(status.toString());
    updateCustodyTransaction(txn);
  }

  public void updateCustodyTransaction(JdbcCustodyTransaction txn) {
    txn.setUpdatedAt(Instant.now());
    custodyTransactionRepo.save(txn);
  }

  public List<JdbcCustodyTransaction> getOutboundTransactionsEligibleForReconciliation() {
    return custodyTransactionRepo.findAllByStatusAndExternalTxIdNotNull(SUBMITTED.toString());
  }

  public List<JdbcCustodyTransaction> getInboundTransactionsEligibleForReconciliation() {
    return custodyTransactionRepo.findAllByStatusAndKindIn(
        CREATED.toString(), Set.of(RECEIVE.getKind(), WITHDRAWAL.getKind()));
  }

  private AnchorException getResponseException(FireblocksException e) {
    switch (HttpStatus.valueOf(e.getStatusCode())) {
      case TOO_MANY_REQUESTS:
        return new CustodyTooManyRequestsException(e.getRawMessage());
      case BAD_REQUEST:
        return new CustodyBadRequestException(e.getRawMessage());
      case SERVICE_UNAVAILABLE:
        return new CustodyServiceUnavailableException(e.getRawMessage());
      default:
        debugF("Unhandled status code (%s)", e.getStatusCode());
        return e;
    }
  }
}
