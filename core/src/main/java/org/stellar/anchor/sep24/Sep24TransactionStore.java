package org.stellar.anchor.sep24;

import java.util.List;
import org.stellar.anchor.api.exception.SepException;
import org.stellar.anchor.api.sep.sep24.GetTransactionsRequest;
import org.stellar.anchor.util.TransactionsParams;

/** This interface is for the SEP adapter service to query/save the transaction document. */
@SuppressWarnings("RedundantThrows")
public interface Sep24TransactionStore {
  Sep24Transaction newInstance();

  Sep24Refunds newRefunds();

  Sep24RefundPayment newRefundPayment();

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
   * @param assetCode The asset code to match. It wins over {@code request.getAssetCode()}, which
   *     may still hold the unparsed {@code stellar:CODE[:ISSUER]} form the caller sent.
   * @param assetIssuer The asset issuer to match, or null to match the code alone. A transaction
   *     stored with no issuer matches any issuer, because a deposit keeps the issuer exactly as the
   *     wallet sent it.
   * @param request The query request.
   * @return The list of transaction documents. If not found, return empty list.
   * @throws SepException if error happens
   */
  List<Sep24Transaction> findTransactions(
      String accountId,
      String accountMemo,
      String assetCode,
      String assetIssuer,
      GetTransactionsRequest request)
      throws SepException;

  /**
   * Save a transaction.
   *
   * @param sep24Transaction The transaction to be saved.
   * @return The saved transaction.
   * @throws SepException SepException.
   */
  @SuppressWarnings("UnusedReturnValue")
  Sep24Transaction save(Sep24Transaction sep24Transaction) throws SepException;

  /**
   * Finds multiple transactions that matches following criteria
   *
   * @param params parameters for transaction search
   * @return list of transactions
   */
  List<? extends Sep24Transaction> findTransactions(TransactionsParams params);

  /**
   * Find the withdrawals that wait for a payment to the given account with the given memo, in the
   * given status. Used to tell whether a memo is already assigned and to route an incoming payment.
   *
   * @param withdrawAnchorAccount The account the user pays.
   * @param memo The memo the user pays with.
   * @param status The status the withdrawal must be in.
   * @return The matching transactions, empty if none.
   */
  List<? extends Sep24Transaction> findAllByWithdrawAnchorAccountAndMemoAndStatus(
      String withdrawAnchorAccount, String memo, String status);
}
