package org.stellar.anchor.sep31;

import java.util.Collection;
import java.util.List;
import lombok.NonNull;
import org.stellar.anchor.api.exception.AnchorException;
import org.stellar.anchor.api.exception.SepException;
import org.stellar.anchor.util.TransactionsParams;

@SuppressWarnings("RedundantThrows")
public interface Sep31TransactionStore {
  Sep31Transaction newTransaction();

  Sep31Refunds newRefunds();

  RefundPayment newRefundPayment();

  /**
   * Find the Sep31Transaction by transaction_id
   *
   * @param transactionId The transaction ID.
   * @return The transaction document. null if not found.
   * @throws AnchorException if error happens.
   */
  Sep31Transaction findByTransactionId(String transactionId) throws AnchorException;

  /**
   * Find the transactions by the collection of ids.
   *
   * @param transactionIds Collection of ids.
   * @return List of transactions.
   * @throws AnchorException if error happens.
   */
  List<? extends Sep31Transaction> findByTransactionIds(@NonNull Collection<String> transactionIds)
      throws AnchorException;

  /**
   * Find the transactions by the transaction memo.
   *
   * @param memo transaction memo.
   * @return The matching transaction.
   * @throws AnchorException if error happens.
   */
  Sep31Transaction findByStellarMemo(@NonNull String memo) throws AnchorException;

  /**
   * Save a transaction.
   *
   * @param sep31Transaction The transaction to be saved.
   * @return The saved transaction.
   * @throws SepException if error happens.
   */
  @SuppressWarnings("UnusedReturnValue")
  Sep31Transaction save(Sep31Transaction sep31Transaction) throws SepException;

  /**
   * Finds multiple transactions that matches following criteria
   *
   * @param params parameters for transaction search
   * @return list of transactions
   */
  List<? extends Sep31Transaction> findTransactions(TransactionsParams params);

  /**
   * Find the transactions that wait for a payment to the given account with the given memo, in the
   * given status. Used to tell whether a memo is already assigned and to route an incoming payment.
   */
  List<? extends Sep31Transaction> findAllByToAccountAndMemoAndStatus(
      String toAccount, String memo, String status);
}
