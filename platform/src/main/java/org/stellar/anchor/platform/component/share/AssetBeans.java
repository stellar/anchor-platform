package org.stellar.anchor.platform.component.share;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.stellar.anchor.api.exception.InvalidConfigException;
import org.stellar.anchor.asset.AssetService;
import org.stellar.anchor.config.AssetsConfig;
import org.stellar.anchor.platform.config.PropertyAssetsConfig;
import org.stellar.anchor.platform.service.PropertyAssetsService;

@Configuration
public class AssetBeans {
  @Bean
  @ConfigurationProperties(prefix = "assets")
  AssetsConfig assetsConfig() {
    return new PropertyAssetsConfig();
  }

  @Bean
  AssetService assetService(AssetsConfig assetsConfig) throws InvalidConfigException {
    return new PropertyAssetsService(assetsConfig);
  }
}
