package org.stellar.anchor.platform.component.platform;

import jakarta.servlet.Filter;
import java.util.Optional;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.stellar.anchor.api.exception.InvalidConfigException;
import org.stellar.anchor.asset.AssetService;
import org.stellar.anchor.auth.JwtService;
import org.stellar.anchor.config.CustodyConfig;
import org.stellar.anchor.config.Sep24Config;
import org.stellar.anchor.config.Sep31Config;
import org.stellar.anchor.config.Sep6Config;
import org.stellar.anchor.custody.CustodyService;
import org.stellar.anchor.event.EventService;
import org.stellar.anchor.filter.ApiKeyFilter;
import org.stellar.anchor.filter.NoneFilter;
import org.stellar.anchor.filter.PlatformAuthJwtFilter;
import org.stellar.anchor.network.Horizon;
import org.stellar.anchor.platform.apiclient.CustodyApiClient;
import org.stellar.anchor.platform.config.PlatformApiConfig;
import org.stellar.anchor.platform.config.PlatformServerConfig;
import org.stellar.anchor.platform.config.PropertyCustodyConfig;
import org.stellar.anchor.platform.data.JdbcTransactionPendingTrustRepo;
import org.stellar.anchor.platform.job.TrustlineCheckJob;
import org.stellar.anchor.platform.rpc.NotifyTrustSetHandler;
import org.stellar.anchor.platform.service.*;
import org.stellar.anchor.sep24.Sep24DepositInfoGenerator;
import org.stellar.anchor.sep24.Sep24TransactionStore;
import org.stellar.anchor.sep31.Sep31DepositInfoGenerator;
import org.stellar.anchor.sep31.Sep31TransactionStore;
import org.stellar.anchor.sep38.Sep38QuoteStore;
import org.stellar.anchor.sep6.Sep6DepositInfoGenerator;
import org.stellar.anchor.sep6.Sep6TransactionStore;

@Configuration
public class PlatformServerBeans {

  /**
   * Register anchor-to-platform token filter.
   *
   * @return Spring Filter Registration Bean
   */
  @Bean
  public FilterRegistrationBean<Filter> platformTokenFilter(
      PlatformServerConfig serverConfig, PlatformApiConfig apiConfig) {
    Filter anchorToPlatformFilter;
    String authSecret = serverConfig.getSecretConfig().getPlatformAuthSecret();
    switch (apiConfig.getAuth().getType()) {
      case JWT:
        JwtService jwtService = JwtService.builder().platformAuthSecret(authSecret).build();
        anchorToPlatformFilter =
            new PlatformAuthJwtFilter(jwtService, apiConfig.getAuth().getJwt().getHttpHeader());
        break;

      case API_KEY:
        anchorToPlatformFilter =
            new ApiKeyFilter(authSecret, apiConfig.getAuth().getApiKey().getHttpHeader());
        break;

      default:
        anchorToPlatformFilter = new NoneFilter();
        break;
    }

    FilterRegistrationBean<Filter> registrationBean = new FilterRegistrationBean<>();
    registrationBean.setFilter(anchorToPlatformFilter);
    return registrationBean;
  }

  @Bean
  Sep31DepositInfoGenerator sep31DepositInfoGenerator(
      Sep31Config sep31Config, Optional<CustodyApiClient> custodyApiClient)
      throws InvalidConfigException {
    switch (sep31Config.getDepositInfoGeneratorType()) {
      case SELF:
        return new Sep31DepositInfoSelfGenerator();
      case CUSTODY:
        return new Sep31DepositInfoCustodyGenerator(
            custodyApiClient.orElseThrow(
                () ->
                    new InvalidConfigException("Integration with custody service is not enabled")));
      case NONE:
        return new Sep31DepositInfoNoneGenerator();
      default:
        throw new RuntimeException(
            String.format(
                "sep31.deposit_info_generator_type: %s is not supported",
                sep31Config.getDepositInfoGeneratorType()));
    }
  }

  @Bean
  Sep24DepositInfoGenerator sep24DepositInfoGenerator(
      Sep24Config sep24Config, Optional<CustodyApiClient> custodyApiClient)
      throws InvalidConfigException {
    switch (sep24Config.getDepositInfoGeneratorType()) {
      case SELF:
        return new Sep24DepositInfoSelfGenerator();
      case CUSTODY:
        return new Sep24DepositInfoCustodyGenerator(
            custodyApiClient.orElseThrow(
                () ->
                    new InvalidConfigException("Integration with custody service is not enabled")));
      case NONE:
        return new Sep24DepositInfoNoneGenerator();
      default:
        throw new RuntimeException(
            String.format(
                "sep24.deposit_info_generator_type: %s is not supported",
                sep24Config.getDepositInfoGeneratorType()));
    }
  }

  @Bean
  Sep6DepositInfoGenerator sep6DepositInfoGenerator(
      Sep6Config sep6Config, AssetService assetService, Optional<CustodyApiClient> custodyApiClient)
      throws InvalidConfigException {
    switch (sep6Config.getDepositInfoGeneratorType()) {
      case SELF:
        return new Sep6DepositInfoSelfGenerator(assetService);
      case CUSTODY:
        return new Sep6DepositInfoCustodyGenerator(
            custodyApiClient.orElseThrow(
                () ->
                    new InvalidConfigException("Integration with custody service is not enabled")));
      case NONE:
        return new Sep6DepositInfoNoneGenerator();
      default:
        throw new RuntimeException(
            String.format(
                "sep6.deposit_info_generator_type: %s is not supported",
                sep6Config.getDepositInfoGeneratorType()));
    }
  }

  @Bean
  TransactionService transactionService(
      Sep6TransactionStore txn6Store,
      Sep24TransactionStore txn24Store,
      Sep31TransactionStore txn31Store,
      Sep38QuoteStore quoteStore,
      AssetService assetService,
      EventService eventService,
      Sep6DepositInfoGenerator sep6DepositInfoGenerator,
      Sep24DepositInfoGenerator sep24DepositInfoGenerator,
      CustodyService custodyService,
      CustodyConfig custodyConfig) {
    return new TransactionService(
        txn6Store,
        txn24Store,
        txn31Store,
        quoteStore,
        assetService,
        eventService,
        sep6DepositInfoGenerator,
        sep24DepositInfoGenerator,
        custodyService,
        custodyConfig);
  }

  @Bean
  TrustlineCheckJob trustlineCheckJob(
      Horizon horizon,
      JdbcTransactionPendingTrustRepo transactionPendingTrustRepo,
      PropertyCustodyConfig custodyConfig,
      NotifyTrustSetHandler notifyTrustSetHandler) {
    if (custodyConfig.isCustodyIntegrationEnabled()) {
      return new TrustlineCheckJob(
          horizon, transactionPendingTrustRepo, custodyConfig, notifyTrustSetHandler);
    } else {
      return null;
    }
  }
}
