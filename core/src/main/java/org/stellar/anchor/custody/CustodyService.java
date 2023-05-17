package org.stellar.anchor.custody;

import org.stellar.anchor.api.custody.CreateTransactionPaymentResponse;
import org.stellar.anchor.api.exception.AnchorException;
import org.stellar.anchor.sep24.Sep24Transaction;
import org.stellar.anchor.sep31.Sep31Transaction;

public interface CustodyService {

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
}
