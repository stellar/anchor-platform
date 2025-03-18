package org.stellar.anchor.ledger;

import static org.stellar.anchor.api.asset.AssetInfo.NATIVE_ASSET_CODE;
import static org.stellar.sdk.xdr.SignerKeyType.*;

import java.time.Instant;
import java.util.List;
import java.util.stream.Collectors;
import lombok.Getter;
import org.stellar.anchor.config.AppConfig;
import org.stellar.anchor.ledger.LedgerTransaction.LedgerTransactionResponse;
import org.stellar.anchor.util.AssetHelper;
import org.stellar.sdk.*;
import org.stellar.sdk.exception.NetworkException;
import org.stellar.sdk.requests.PaymentsRequestBuilder;
import org.stellar.sdk.responses.AccountResponse;
import org.stellar.sdk.responses.TransactionResponse;
import org.stellar.sdk.responses.operations.OperationResponse;
import org.stellar.sdk.xdr.AssetType;
import org.stellar.sdk.xdr.SignerKeyType;

/** The horizon-server. */
public class Horizon implements LedgerClient {
  @Getter private final String horizonUrl;
  @Getter private final String stellarNetworkPassphrase;
  private final Server horizonServer;

  public Horizon(AppConfig appConfig) {
    this.horizonUrl = appConfig.getHorizonUrl();
    this.stellarNetworkPassphrase = appConfig.getStellarNetworkPassphrase();
    this.horizonServer = new Server(appConfig.getHorizonUrl());
  }

  public Server getServer() {
    return this.horizonServer;
  }

  @Override
  public boolean hasTrustline(String account, String asset) throws NetworkException {
    String assetCode = AssetHelper.getAssetCode(asset);
    if (NATIVE_ASSET_CODE.equals(assetCode)) {
      return true;
    }
    String assetIssuer = AssetHelper.getAssetIssuer(asset);

    AccountResponse accountResponse = getServer().accounts().account(account);
    return accountResponse.getBalances().stream()
        .anyMatch(
            balance -> {
              TrustLineAsset trustLineAsset = balance.getTrustLineAsset();
              if (trustLineAsset.getAssetType() == AssetType.ASSET_TYPE_CREDIT_ALPHANUM4
                  || trustLineAsset.getAssetType() == AssetType.ASSET_TYPE_CREDIT_ALPHANUM12) {
                AssetTypeCreditAlphaNum creditAsset =
                    (AssetTypeCreditAlphaNum) trustLineAsset.getAsset();
                assert creditAsset != null;
                return creditAsset.getCode().equals(assetCode)
                    && creditAsset.getIssuer().equals(assetIssuer);
              }
              return false;
            });
  }

  @Override
  public Account getAccount(String account) throws NetworkException {
    AccountResponse response = getServer().accounts().account(account);
    AccountResponse.Thresholds thresholds = response.getThresholds();

    return Account.builder()
        .accountId(response.getAccountId())
        .sequenceNumber(response.getSequenceNumber())
        .thresholds(
            LedgerClient.Thresholds.builder()
                .low(thresholds.getLowThreshold())
                .medium(thresholds.getMedThreshold())
                .high(thresholds.getHighThreshold())
                .build())
        .signers(
            response.getSigners().stream()
                .map(
                    s ->
                        Signer.builder()
                            .key(s.getKey())
                            .type(getKeyTypeDiscriminant(s.getType()).name())
                            .weight((long) s.getWeight())
                            .build())
                .collect(Collectors.toList()))
        .build();
  }

  @Override
  public LedgerTransaction getTransaction(String txnHash) throws NetworkException {
    TransactionResponse response = getServer().transactions().transaction(txnHash);
    return LedgerTransaction.builder()
        .hash(response.getHash())
        .sourceAccount(response.getSourceAccount())
        .envelopeXdr(response.getEnvelopeXdr())
        .memo(response.getMemo())
        .sequenceNumber(response.getSourceAccountSequence())
        .createdAt(Instant.parse(response.getCreatedAt()))
        .build();
  }

  @Override
  public LedgerTransactionResponse submitTransaction(Transaction transaction)
      throws NetworkException {
    TransactionResponse txnR = getServer().submitTransaction(transaction, false);

    return LedgerTransactionResponse.builder()
        .hash(txnR.getHash())
        .envelopXdr(txnR.getEnvelopeXdr())
        .sourceAccount(txnR.getSourceAccount())
        .feeCharged(txnR.getFeeCharged().toString())
        .createdAt(Instant.parse(txnR.getCreatedAt()))
        .build();
  }

  /**
   * Get payment operations for a transaction.
   *
   * @param stellarTxnId the transaction id
   * @return the operations
   * @throws NetworkException request failed, see {@link PaymentsRequestBuilder#execute()}
   */
  public List<OperationResponse> getStellarTxnOperations(String stellarTxnId) {
    return getServer()
        .payments()
        .includeTransactions(true)
        .forTransaction(stellarTxnId)
        .execute()
        .getRecords();
  }

  public SignerKeyType getKeyTypeDiscriminant(String type) {
    return switch (type) {
      case "ed25519_public_key" -> SIGNER_KEY_TYPE_ED25519;
      case "preauth_tx" -> SIGNER_KEY_TYPE_PRE_AUTH_TX;
      case "sha256_hash" -> SIGNER_KEY_TYPE_HASH_X;
      case "ed25519_signed_payload" -> SIGNER_KEY_TYPE_ED25519_SIGNED_PAYLOAD;
      default -> throw new IllegalArgumentException("Invalid signer key type: " + type);
    };
  }
}
