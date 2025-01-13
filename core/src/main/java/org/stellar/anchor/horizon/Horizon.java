package org.stellar.anchor.horizon;

import static org.stellar.anchor.api.asset.AssetInfo.NATIVE_ASSET_CODE;

import java.util.List;
import lombok.Getter;
import org.stellar.anchor.config.AppConfig;
import org.stellar.anchor.util.AssetHelper;
import org.stellar.sdk.AssetTypeCreditAlphaNum;
import org.stellar.sdk.Server;
import org.stellar.sdk.TrustLineAsset;
import org.stellar.sdk.exception.NetworkException;
import org.stellar.sdk.requests.PaymentsRequestBuilder;
import org.stellar.sdk.responses.AccountResponse;
import org.stellar.sdk.responses.operations.OperationResponse;
import org.stellar.sdk.xdr.AssetType;

/** The horizon-server. */
public class Horizon {

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

  public boolean isTrustlineConfigured(String account, String asset) throws NetworkException {
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
