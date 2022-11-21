package org.stellar.anchor.sep24;

import java.util.List;
import org.stellar.anchor.api.exception.SepException;
import org.stellar.anchor.api.sep.sep24.GetTransactionsRequest;

/** This interface is for the SEP adapter service to query/save the transaction document. */
@SuppressWarnings("RedundantThrows")
public interface Sep24TransactionStore {
  Sep24Transaction newInstance();

  /**
   * Find the Sep24Transaction by transaction_id
   *
   * @param transactionId The transaction ID
   * @return The transaction document. null if not found.
   * @throws SepException if error happens
   */
  Sep24Transaction findByTransactionId(String transactionId) throws SepException;

  /**
   * Find the Sep24Transaction by the stellar network transaction id (hash)
   *
   * @param stellarTransactionId The Stellar transaction id (hash)
   * @return The transaction document. null if not found.
   * @throws SepException if error happens
   */
  Sep24Transaction findByStellarTransactionId(String stellarTransactionId) throws SepException;

  /**
   * Find the Sep24Transaction by the anchor's transaction Id.
   *
   * @param externalTransactionId The anchor's transaction id.
   * @return The transaction document. null if not found.
   * @throws SepException if error happens
   */
  Sep24Transaction findByExternalTransactionId(String externalTransactionId) throws SepException;

  /**
   * Find the transactions filtered and limited the request
   *
   * @param accountId The authenticating Stellar account id.
   * @param accountMemo The memo of the authenticating account.
   * @param request The query request.
   * @return The list of transaction documents. If not found, return empty list.
   * @throws SepException if error happens
   */
  List<Sep24Transaction> findTransactions(
      String accountId, String accountMemo, GetTransactionsRequest request) throws SepException;
  /**
   * Save a transaction.
   *
   * @param sep24Transaction The transaction to be saved.
   * @return The saved transaction.
   * @throws SepException SepException.
   */
  @SuppressWarnings("UnusedReturnValue")
  Sep24Transaction save(Sep24Transaction sep24Transaction) throws SepException;
}
