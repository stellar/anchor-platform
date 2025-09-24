package org.stellar.anchor.asset;

import static org.stellar.anchor.util.Log.infoF;

import com.google.gson.Gson;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;
import lombok.NoArgsConstructor;
import org.apache.commons.io.FilenameUtils;
import org.stellar.anchor.api.exception.InvalidConfigException;
import org.stellar.anchor.api.exception.SepNotFoundException;
import org.stellar.anchor.api.sep.AssetInfo;
import org.stellar.anchor.config.AssetsConfig;
import org.stellar.anchor.util.FileUtil;
import org.stellar.anchor.util.GsonUtils;
import org.yaml.snakeyaml.Yaml;

@NoArgsConstructor
public class DefaultAssetService implements AssetService {
  static final Gson gson = GsonUtils.getInstance();
  Assets assets;

  public static DefaultAssetService fromAssetConfig(AssetsConfig assetsConfig)
      throws InvalidConfigException {
    switch (assetsConfig.getType()) {
      case JSON:
        return fromJson(assetsConfig.getValue());
      case YAML:
        return fromYaml(assetsConfig.getValue());
      case FILE:
        String filename = assetsConfig.getValue();
        try {
          String content = FileUtil.read(Path.of(filename));
          switch (FilenameUtils.getExtension(filename).toLowerCase()) {
            case "json":
              return fromJson(content);
            case "yaml":
            case "yml":
              return fromYaml(content);
            default:
              throw new InvalidConfigException(
                  String.format("%s is not a supported file format", filename));
          }
        } catch (Exception ex) {
          throw new InvalidConfigException(
              List.of(String.format("Cannot read from asset file: %s", filename)), ex);
        }
      case URL:
        // TODO: to be implemented.
      default:
        infoF("assets type {} is not supported", assetsConfig.getType());
        throw new InvalidConfigException(
            String.format("assets type %s is not supported", assetsConfig.getType()));
    }
  }

  public static DefaultAssetService fromYaml(String assetsYaml) throws InvalidConfigException {
    // snakeyaml does not support mapping snake-cased fields to camelCased fields.
    // So we are converting to JSON and use the gson library for conversion
    Map<String, Object> map = new Yaml().load(assetsYaml);
    return fromJson(gson.toJson(map));
  }

  public static DefaultAssetService fromJson(String assetsJson) throws InvalidConfigException {
    DefaultAssetService assetService = new DefaultAssetService();
    assetService.assets = gson.fromJson(assetsJson, Assets.class);
    assetService.assets.assets.removeIf(Objects::isNull);
    AssetServiceValidator.validate(assetService);
    return assetService;
  }

  public static DefaultAssetService fromJsonResource(String resourcePath)
      throws IOException, SepNotFoundException, InvalidConfigException {
    return fromJson(FileUtil.getResourceFileAsString(resourcePath));
  }

  public static DefaultAssetService fromYamlResource(String resourcePath)
      throws IOException, SepNotFoundException, InvalidConfigException {
    return fromYaml(FileUtil.getResourceFileAsString(resourcePath));
  }

  @Override
  public List<AssetInfo> listAllAssets() {
    return new ArrayList<>(assets.getAssets());
  }

  @Override
  public AssetInfo getAsset(String code) {
    for (AssetInfo asset : assets.getAssets()) {
      if (asset != null && asset.getCode().equals(code)) {
        return asset;
      }
    }
    return null;
  }

  @Override
  public AssetInfo getAsset(String code, String issuer) {
    if (issuer == null) {
      return getAsset(code);
    }
    for (AssetInfo asset : assets.getAssets()) {
      if (asset.getCode().equals(code) && issuer.equals(asset.getIssuer())) {
        return asset;
      }
    }
    return null;
  }

  @Override
  public AssetInfo getAssetByName(String name) {
    for (AssetInfo asset : assets.getAssets()) {
      if (asset.getSep38AssetName().equals(name)) {
        return asset;
      }
    }
    return null;
  }

  @Override
  public List<AssetInfo> listStellarAssets() {
    return listAllAssets().stream()
        .filter(asset -> asset.getSchema().equals(AssetInfo.Schema.stellar))
        .collect(Collectors.toList());
  }
}
