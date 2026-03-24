package org.stellar.anchor.custody;

import org.stellar.anchor.api.custody.CreateTransactionPaymentResponse;
import org.stellar.anchor.api.exception.AnchorException;
import org.stellar.anchor.api.rpc.method.DoStellarRefundRequest;
import org.stellar.anchor.sep24.Sep24Transaction;
import org.stellar.anchor.sep31.Sep31Transaction;
import org.stellar.anchor.sep6.Sep6Transaction;

public interface CustodyService {

  /**
   * Create custody transaction for SEP6 transaction
   *
   * @param txn SEP6 transaction
   * @throws AnchorException if error happens
   */
  void createTransaction(Sep6Transaction txn) throws AnchorException;

  /**
   * Create custody transaction for SEP24 transaction
   *
   * @param txn SEP24 transaction
   * @throws AnchorException if error happens
   */
  void createTransaction(Sep24Transaction txn) throws AnchorException;

  /**
   * Create custody transaction for SEP31 transaction
   *
   * @param txn SEP31 transaction
   * @throws AnchorException if error happens
   */
  void createTransaction(Sep31Transaction txn) throws AnchorException;

  /**
   * Create custody transaction payment
   *
   * @param txnId transaction ID
   * @param requestBody request body
   * @return {@link CreateTransactionPaymentResponse} object
   * @throws AnchorException if error happens
   */
  CreateTransactionPaymentResponse createTransactionPayment(String txnId, String requestBody)
      throws AnchorException;

  /**
   * Create custody transaction refund
   *
   * @param refundRequest {@link DoStellarRefundRequest} object
   * @param memo Refund memo
   * @param memoType Refund memo type
   * @return {@link CreateTransactionPaymentResponse} object
   * @throws AnchorException if error happens
   */
  CreateTransactionPaymentResponse createTransactionRefund(
      DoStellarRefundRequest refundRequest, String memo, String memoType) throws AnchorException;
}
