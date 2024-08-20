package org.stellar.anchor.api.asset;

import com.google.gson.annotations.SerializedName;
import org.stellar.anchor.api.sep.AssetInfo.DepositWithdrawInfo;
import org.stellar.anchor.api.sep.AssetInfo.Schema;
import org.stellar.anchor.api.sep.operation.Sep31Info;
import org.stellar.anchor.api.sep.operation.Sep38Info;
import org.stellar.anchor.api.sep.sep31.Sep31InfoResponse;
import org.stellar.anchor.api.sep.sep38.InfoResponse;

public class StellarAssetInfo implements AssetInfo {
  String code;
  String issuer;

  @SerializedName("distribution_account")
  String distributionAccount;

  @SerializedName("significant_decimals")
  Integer significantDecimals;

  DepositWithdrawInfo sep6;
  DepositWithdrawInfo sep24;
  Sep31Info sep31;
  Sep38Info sep38;

  @Override
  public Schema getSchema() {
    return Schema.STELLAR;
  }

  @Override
  public String getAssetIdentificationName() {
    return getSchema() + ":" + code + ":" + issuer;
  }

  @Override
  public InfoResponse.Asset toSEP38InfoResponseAsset() {
    return null;
  }

  @Override
  public Sep31InfoResponse.AssetResponse toSEP31InfoResponseAsset() {
    return null;
  }
}
