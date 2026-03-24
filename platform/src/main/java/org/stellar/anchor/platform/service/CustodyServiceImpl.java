package org.stellar.anchor.platform.service;

import static org.stellar.anchor.util.Log.debugF;
import static org.stellar.anchor.util.TransactionMapper.toCustodyTransaction;

import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.stellar.anchor.api.custody.CreateCustodyTransactionRequest;
import org.stellar.anchor.api.custody.CreateTransactionPaymentResponse;
import org.stellar.anchor.api.custody.CreateTransactionRefundRequest;
import org.stellar.anchor.api.exception.AnchorException;
import org.stellar.anchor.api.exception.CustodyException;
import org.stellar.anchor.api.exception.InvalidConfigException;
import org.stellar.anchor.api.exception.custody.CustodyBadRequestException;
import org.stellar.anchor.api.exception.custody.CustodyNotFoundException;
import org.stellar.anchor.api.exception.custody.CustodyServiceUnavailableException;
import org.stellar.anchor.api.exception.custody.CustodyTooManyRequestsException;
import org.stellar.anchor.api.rpc.method.DoStellarRefundRequest;
import org.stellar.anchor.custody.CustodyService;
import org.stellar.anchor.platform.apiclient.CustodyApiClient;
import org.stellar.anchor.sep24.Sep24Transaction;
import org.stellar.anchor.sep31.Sep31Transaction;
import org.stellar.anchor.sep6.Sep6Transaction;

public class CustodyServiceImpl implements CustodyService {

  private final Optional<CustodyApiClient> custodyApiClient;

  public CustodyServiceImpl(Optional<CustodyApiClient> custodyApiClient) {
    this.custodyApiClient = custodyApiClient;
  }

  @Override
  public void createTransaction(Sep6Transaction txn) throws AnchorException {
    create(toCustodyTransaction(txn));
  }

  @Override
  public void createTransaction(Sep24Transaction txn) throws AnchorException {
    create(toCustodyTransaction(txn));
  }

  @Override
  public void createTransaction(Sep31Transaction txn) throws AnchorException {
    create(toCustodyTransaction(txn));
  }

  @Override
  public CreateTransactionPaymentResponse createTransactionPayment(String txnId, String requestBody)
      throws AnchorException {
    if (custodyApiClient.isEmpty()) {
      // custody.type is set to 'none'
      throw new InvalidConfigException("Integration with custody service is not enabled");
    }

    try {
      return custodyApiClient.get().createTransactionPayment(txnId, requestBody);
    } catch (CustodyException e) {
      throw (getResponseException(e));
    }
  }

  @Override
  public CreateTransactionPaymentResponse createTransactionRefund(
      DoStellarRefundRequest request, String memo, String memoType) throws AnchorException {
    if (custodyApiClient.isEmpty()) {
      // custody.type is set to 'none'
      throw new InvalidConfigException("Integration with custody service is not enabled");
    }

    CreateTransactionRefundRequest createTransactionRefundRequest =
        CreateTransactionRefundRequest.builder()
            .amount(request.getRefund().getAmount().getAmount())
            .amountFee(request.getRefund().getAmountFee().getAmount())
            .memo(memo)
            .memoType(memoType)
            .build();

    try {
      return custodyApiClient
          .get()
          .createTransactionRefund(request.getTransactionId(), createTransactionRefundRequest);
    } catch (CustodyException e) {
      throw (getResponseException(e));
    }
  }

  private void create(CreateCustodyTransactionRequest request)
      throws CustodyException, InvalidConfigException {
    if (custodyApiClient.isEmpty()) {
      // custody.type is set to 'none'
      throw new InvalidConfigException("Integration with custody service is not enabled");
    }
    custodyApiClient.get().createTransaction(request);
  }

  public AnchorException getResponseException(CustodyException e) {
    switch (HttpStatus.valueOf(e.getStatusCode())) {
      case NOT_FOUND:
        return new CustodyNotFoundException(e.getRawMessage());
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
