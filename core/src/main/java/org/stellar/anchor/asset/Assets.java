package org.stellar.anchor.asset;

import java.util.List;
import lombok.Data;
import org.stellar.anchor.api.asset.Asset;

@Data
public class Assets {
  List<Asset> assets;
}
