package org.stellar.anchor.horizon;

import static org.stellar.anchor.api.asset.AssetInfo.NATIVE_ASSET_CODE;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import lombok.Getter;
import org.stellar.anchor.config.AppConfig;
import org.stellar.anchor.util.AssetHelper;
import org.stellar.sdk.AssetTypeCreditAlphaNum;
import org.stellar.sdk.Server;
import org.stellar.sdk.responses.AccountResponse;
import org.stellar.sdk.responses.operations.OperationResponse;

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

  public boolean isTrustlineConfigured(String account, String asset) throws IOException {
    String assetCode = AssetHelper.getAssetCode(asset);
    if (NATIVE_ASSET_CODE.equals(assetCode)) {
      return true;
    }
    String assetIssuer = AssetHelper.getAssetIssuer(asset);

    AccountResponse accountResponse = getServer().accounts().account(account);
    return Arrays.stream(accountResponse.getBalances())
        .anyMatch(
            balance -> {
              if (balance.getAssetType().equals("credit_alphanum4")
                  || balance.getAssetType().equals("credit_alphanum12")) {
                AssetTypeCreditAlphaNum creditAsset =
                    (AssetTypeCreditAlphaNum) balance.getAsset().get();
                return creditAsset.getCode().equals(assetCode)
                    && creditAsset.getIssuer().equals(assetIssuer);
              }
              return false;
            });
  }

  public List<OperationResponse> getStellarTxnOperations(String stellarTxnId) throws IOException {
    return getServer()
        .payments()
        .includeTransactions(true)
        .forTransaction(stellarTxnId)
        .execute()
        .getRecords();
  }
}
