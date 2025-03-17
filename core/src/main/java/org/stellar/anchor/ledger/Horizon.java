package org.stellar.anchor.ledger;

import static org.stellar.anchor.api.asset.AssetInfo.NATIVE_ASSET_CODE;

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
                .lowThreshold(thresholds.getLowThreshold())
                .medThreshold(thresholds.getMedThreshold())
                .highThreshold(thresholds.getHighThreshold())
                .build())
        .signers(
            response.getSigners().stream()
                .map(
                    s ->
                        Signer.builder()
                            .key(s.getKey())
                            .type(s.getType())
                            .weight(s.getWeight())
                            .sponsor(s.getSponsor())
                            .build())
                .collect(Collectors.toList()))
        .build();
  }

  @Override
  public LedgerTransaction getTransaction(String transactionId) throws NetworkException {
    TransactionResponse response = getServer().transactions().transaction(transactionId);
    return LedgerTransaction.builder()
        .hash(response.getHash())
        .sourceAccount(response.getSourceAccount())
        .envelopeXdr(response.getEnvelopeXdr())
        .sourceAccount(response.getSourceAccount())
        .memo(response.getMemo())
        .sequenceNumber(response.getSourceAccountSequence())
        .createdAt(response.getCreatedAt())
        .build();
  }

  @Override
  public LedgerTransactionResponse submitTransaction(Transaction transaction)
      throws NetworkException {
    TransactionResponse txnR = getServer().submitTransaction(transaction, false);

    return LedgerTransactionResponse.builder()
        .hash(txnR.getHash())
        .metaXdr(txnR.getEnvelopeXdr())
        .envelopXdr(txnR.getEnvelopeXdr())
        .sourceAccount(txnR.getSourceAccount())
        .feeCharged(txnR.getFeeCharged().toString())
        .createdAt(txnR.getCreatedAt())
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
}
