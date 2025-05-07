package org.stellar.anchor.sep24;

import java.util.Map;
import org.stellar.anchor.api.asset.AssetInfo;
import org.stellar.anchor.auth.WebAuthJwt;

public abstract class InteractiveUrlConstructor {
  public abstract String construct(
      Sep24Transaction txn, Map<String, String> request, AssetInfo asset, WebAuthJwt jwt);
}
