package org.stellar.anchor.ledger;

import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.Value;
import org.stellar.anchor.api.exception.LedgerException;
import org.stellar.sdk.KeyPair;
import org.stellar.sdk.Transaction;
import org.stellar.sdk.TransactionBuilderAccount;

public interface LedgerClient {
  /**
   * Check if the account has a trustline for the given asset.
   *
   * @param account The account to check.
   * @param asset The asset to check.
   * @return True if the account has a trustline for the asset.
   * @throws LedgerException If there was an error communicating with the network.
   */
  boolean hasTrustline(String account, String asset) throws LedgerException;

  /**
   * Get the account details for the given account.
   *
   * @param account The account to get.
   * @return The account details.
   * @throws LedgerException If there was an error communicating with the network.
   */
  Account getAccount(String account) throws LedgerException;

  /**
   * Get the operations for the given Stellar transaction.
   *
   * @param txnHash The Stellar transaction ID.
   * @return The operations for the transaction.
   * @throws LedgerException If there was an error communicating with the network.
   */
  LedgerTransaction getTransaction(String txnHash) throws LedgerException;

  /**
   * Submit a transaction to the network.
   *
   * @param transaction The transaction to submit.
   * @return The transaction response.
   * @throws LedgerException If there was an error communicating with the network.
   */
  LedgerTransaction.LedgerTransactionResponse submitTransaction(Transaction transaction)
      throws LedgerException;

  @Builder
  @Getter
  class Account implements TransactionBuilderAccount {
    private String accountId;
    private Long sequenceNumber;

    private Thresholds thresholds;
    private List<Signer> signers;

    @Override
    public KeyPair getKeyPair() {
      return KeyPair.fromAccountId(accountId);
    }

    @Override
    public void setSequenceNumber(long seqNum) {
      sequenceNumber = seqNum;
    }

    @Override
    public Long getIncrementedSequenceNumber() {
      return sequenceNumber + 1;
    }

    /** Increments sequence number in this object by one. */
    public void incrementSequenceNumber() {
      sequenceNumber++;
    }
  }

  @Builder
  @Getter
  @AllArgsConstructor
  class Thresholds {
    Integer low;
    Integer medium;
    Integer high;
  }

  @Value
  @Builder
  @Getter
  @AllArgsConstructor
  class Signer {
    String key;
    String type;
    Long weight;
  }
}
