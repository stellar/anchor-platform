package org.stellar.anchor.ledger;

import java.io.IOException;
import java.util.List;
import lombok.Builder;
import lombok.Getter;
import lombok.Value;
import org.stellar.sdk.KeyPair;
import org.stellar.sdk.Transaction;
import org.stellar.sdk.TransactionBuilderAccount;
import org.stellar.sdk.exception.NetworkException;
import org.stellar.sdk.responses.TransactionResponse;
import org.stellar.sdk.responses.operations.OperationResponse;

public interface LedgerApi {
  /**
   * Check if the account has a trustline for the given asset.
   *
   * @param account The account to check.
   * @param asset The asset to check.
   * @return True if the account has a trustline for the asset.
   * @throws NetworkException If there was an error communicating with the network.
   */
  boolean hasTrustline(String account, String asset) throws NetworkException, IOException;

  /**
   * Get the account details for the given account.
   *
   * @param account The account to get.
   * @return The account details.
   * @throws NetworkException If there was an error communicating with the network.
   */
  Account getAccount(String account) throws NetworkException;

  /**
   * Get the operations for the given Stellar transaction.
   *
   * @param stellarTxnId The Stellar transaction ID.
   * @return The operations for the transaction.
   */
  List<OperationResponse> getStellarTxnOperations(String stellarTxnId);

  /**
   * Submit a transaction to the network.
   *
   * @param transaction
   * @return The transaction response.
   * @throws NetworkException
   */
  TransactionResponse submitTransaction(Transaction transaction) throws NetworkException;

  @Builder
  @Getter
  class Account implements TransactionBuilderAccount {
    private String accountId;
    private Long sequenceNumber;

    private Thresholds thresholds;
    private List<Balance> balances;
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
  class Thresholds {
    Integer lowThreshold;
    Integer medThreshold;
    Integer highThreshold;
  }

  @Builder
  @Getter
  class Balance {
    String assetType;
    String assetCode;
    String assetIssuer;
    String liquidityPoolId;
    String limit;
    String balance;
  }

  @Value
  @Builder
  @Getter
  class Signer {
    String key;
    String type;
    Integer weight;
    String sponsor;
  }
}
