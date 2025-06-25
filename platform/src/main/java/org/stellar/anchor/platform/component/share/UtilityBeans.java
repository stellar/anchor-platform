package org.stellar.anchor.platform.component.share;

import static org.stellar.anchor.util.StringHelper.isNotEmpty;

import com.google.gson.Gson;
import jakarta.validation.Validator;
import java.util.List;
import lombok.SneakyThrows;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;
import org.stellar.anchor.MoreInfoUrlConstructor;
import org.stellar.anchor.api.exception.NotSupportedException;
import org.stellar.anchor.asset.AssetService;
import org.stellar.anchor.auth.JwtService;
import org.stellar.anchor.client.ClientService;
import org.stellar.anchor.config.*;
import org.stellar.anchor.healthcheck.HealthCheckable;
import org.stellar.anchor.ledger.Horizon;
import org.stellar.anchor.ledger.LedgerClient;
import org.stellar.anchor.ledger.StellarRpc;
import org.stellar.anchor.platform.config.*;
import org.stellar.anchor.platform.service.HealthCheckService;
import org.stellar.anchor.platform.service.Sep24MoreInfoUrlConstructor;
import org.stellar.anchor.platform.service.Sep6MoreInfoUrlConstructor;
import org.stellar.anchor.platform.validator.RequestValidator;
import org.stellar.anchor.util.GsonUtils;

@Configuration
public class UtilityBeans {
  @Bean
  public Gson gson() {
    return GsonUtils.builder().create();
  }

  @Bean
  @DependsOn("configManager")
  public HealthCheckService healthCheckService(List<HealthCheckable> checkables) {
    return new HealthCheckService(checkables);
  }

  @Bean
  @ConfigurationProperties(prefix = "app")
  AppConfig appConfig() {
    return new PropertyAppConfig();
  }

  @Bean
  @Qualifier("sep6MoreInfoUrlConstructor")
  MoreInfoUrlConstructor sep6MoreInfoUrlConstructor(
      AssetService assetService,
      ClientService clientService,
      PropertySep6Config sep6Config,
      JwtService jwtService) {
    return new Sep6MoreInfoUrlConstructor(
        assetService, clientService, sep6Config.getMoreInfoUrl(), jwtService);
  }

  @Bean
  @Qualifier("sep24MoreInfoUrlConstructor")
  MoreInfoUrlConstructor sep24MoreInfoUrlConstructor(
      AssetService assetService,
      ClientService clientService,
      PropertySep24Config sep24Config,
      JwtService jwtService) {
    return new Sep24MoreInfoUrlConstructor(
        assetService, clientService, sep24Config.getMoreInfoUrl(), jwtService);
  }

  @Bean
  @ConfigurationProperties(prefix = "sep31")
  Sep31Config sep31Config(CustodyConfig custodyConfig, AssetService assetService) {
    return new PropertySep31Config(custodyConfig, assetService);
  }

  @Bean
  @ConfigurationProperties(prefix = "sep24")
  PropertySep24Config sep24Config(
      SecretConfig secretConfig, CustodyConfig custodyConfig, AssetService assetService) {
    return new PropertySep24Config(secretConfig, custodyConfig, assetService);
  }

  @Bean
  @ConfigurationProperties(prefix = "sep6")
  PropertySep6Config sep6Config(
      CustodyConfig custodyConfig, AssetService assetService, SecretConfig secretConfig) {
    return new PropertySep6Config(custodyConfig, assetService, secretConfig);
  }

  /**********************************
   * Secret configurations
   */
  @Bean
  PropertySecretConfig secretConfig() {
    return new PropertySecretConfig();
  }

  @Bean
  public JwtService jwtService(SecretConfig secretConfig, CustodySecretConfig custodySecretConfig)
      throws NotSupportedException {
    return new JwtService(secretConfig, custodySecretConfig);
  }

  @Bean
  @SneakyThrows
  public LedgerClient ledgerClient(AppConfig appConfig) {
    if (isNotEmpty(appConfig.getRpcUrl())) return new StellarRpc(appConfig);
    else if (isNotEmpty(appConfig.getHorizonUrl())) return new Horizon(appConfig);
    throw new NotSupportedException("No horizon_url or rpc_url is defined.");
  }

  @Bean
  public RequestValidator requestValidator(Validator validator) {
    return new RequestValidator(validator);
  }
}
