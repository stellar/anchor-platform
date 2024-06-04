package org.stellar.anchor;

import java.time.Instant;
import java.util.List;
import org.stellar.anchor.api.shared.StellarTransaction;

/** This is the interface to tag SEP transaction classes. */
public interface SepTransaction {
  /**
   * The database ID.
   *
   * @return The generated database ID.
   */
  String getId();

  void setId(String id);

  List<StellarTransaction> getStellarTransactions();

  /**
   * Processing status of the transaction
   *
   * @return The <code>status</code> field of the SEP-6, SEP-24 or SEP-31 transaction history.
   */
  String getStatus();

  void setStatus(String status);

  /**
   * Start date and time of transaction.
   *
   * @return The <code>started_at</code> field of the SEP-24 transaction history.
   */
  Instant getStartedAt();

  void setStartedAt(Instant startedAt);

  /**
   * The date and time of transaction last updated.
   *
   * @return <code>updated_at</code> field of the SEP-24 transaction history.
   */
  Instant getUpdatedAt();

  void setUpdatedAt(Instant updatedAt);

  /**
   * The date and time of transaction reaching <code>completed</code> or <code>refunded</code>
   * status.
   *
   * @return <code>completed</code> field of the SEP-24 transaction history.
   */
  Instant getCompletedAt();

  void setCompletedAt(Instant completedAt);
  /**
   * <code>transaction_id</code> on Stellar network of the transfer that either completed the
   * deposit or started the withdrawal.
   *
   * @return The <code>stellar_transction_id</code> field of the SEP-24 transaction history.
   */
  String getStellarTransactionId();

  void setStellarTransactionId(String stellarTransactionId);
}
