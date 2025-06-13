package org.stellar.anchor.platform.service;

import static org.stellar.anchor.util.MemoHelper.memoTypeAsString;
import static org.stellar.sdk.xdr.MemoType.MEMO_ID;

import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.stellar.anchor.api.asset.AssetInfo;
import org.stellar.anchor.api.asset.StellarAssetInfo;
import org.stellar.anchor.api.exception.AnchorException;
import org.stellar.anchor.api.shared.SepDepositInfo;
import org.stellar.anchor.asset.AssetService;
import org.stellar.anchor.sep6.Sep6DepositInfoGenerator;
import org.stellar.anchor.sep6.Sep6Transaction;

@RequiredArgsConstructor
public class Sep6DepositInfoSelfGenerator extends DepositInfoSelfGeneratorBase
    implements Sep6DepositInfoGenerator {
  @NonNull private final AssetService assetService;

  @Override
  public SepDepositInfo generate(Sep6Transaction txn) throws AnchorException {
    AssetInfo assetInfo =
        assetService.getAsset(txn.getRequestAssetCode(), txn.getRequestAssetIssuer());
    return new SepDepositInfo(
        assetInfo instanceof StellarAssetInfo
            ? ((StellarAssetInfo) assetInfo).getDistributionAccount()
            : null,
        generateMemoId(),
        memoTypeAsString(MEMO_ID));
  }
}
