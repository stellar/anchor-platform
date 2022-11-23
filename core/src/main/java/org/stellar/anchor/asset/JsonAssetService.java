package org.stellar.anchor.asset;

import com.google.gson.Gson;
import java.util.ArrayList;
import java.util.List;
import org.stellar.anchor.api.sep.AssetInfo;
import org.stellar.anchor.util.GsonUtils;

public class JsonAssetService implements AssetService {
  static final Gson gson = GsonUtils.getInstance();
  final Assets assets;

  public JsonAssetService(String assetJson) {
    this.assets = gson.fromJson(assetJson, Assets.class);
  }

  public List<AssetInfo> listAllAssets() {
    return new ArrayList<>(assets.assets);
  }

  public AssetInfo getAsset(String code) {
    for (AssetInfo asset : assets.assets) {
      if (asset.getCode().equals(code)) {
        return asset;
      }
    }
    return null;
  }

  public AssetInfo getAsset(String code, String issuer) {
    if (issuer == null) {
      return getAsset(code);
    }
    for (AssetInfo asset : assets.assets) {
      if (asset.getCode().equals(code) && issuer.equals(asset.getIssuer())) {
        return asset;
      }
    }
    return null;
  }
}
