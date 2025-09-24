package org.stellar.anchor.platform.component.sep;

import java.io.IOException;
import java.util.Optional;
import javax.servlet.Filter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.stellar.anchor.MoreInfoUrlConstructor;
import org.stellar.anchor.api.callback.CustomerIntegration;
import org.stellar.anchor.api.callback.FeeIntegration;
import org.stellar.anchor.api.callback.RateIntegration;
import org.stellar.anchor.api.callback.UniqueAddressIntegration;
import org.stellar.anchor.api.exception.InvalidConfigException;
import org.stellar.anchor.apiclient.PlatformApiClient;
import org.stellar.anchor.asset.AssetService;
import org.stellar.anchor.auth.JwtService;
import org.stellar.anchor.client.ClientFinder;
import org.stellar.anchor.config.*;
import org.stellar.anchor.custody.CustodyService;
import org.stellar.anchor.event.EventService;
import org.stellar.anchor.filter.Sep10JwtFilter;
import org.stellar.anchor.horizon.Horizon;
import org.stellar.anchor.platform.apiclient.CustodyApiClient;
import org.stellar.anchor.platform.condition.ConditionalOnAllSepsEnabled;
import org.stellar.anchor.platform.condition.ConditionalOnAnySepsEnabled;
import org.stellar.anchor.platform.config.*;
import org.stellar.anchor.platform.observer.stellar.PaymentObservingAccountsManager;
import org.stellar.anchor.platform.service.Sep31DepositInfoApiGenerator;
import org.stellar.anchor.platform.service.Sep31DepositInfoCustodyGenerator;
import org.stellar.anchor.platform.service.Sep31DepositInfoSelfGenerator;
import org.stellar.anchor.platform.service.SimpleInteractiveUrlConstructor;
import org.stellar.anchor.sep1.Sep1Service;
import org.stellar.anchor.sep10.Sep10Service;
import org.stellar.anchor.sep12.Sep12Service;
import org.stellar.anchor.sep24.InteractiveUrlConstructor;
import org.stellar.anchor.sep24.Sep24Service;
import org.stellar.anchor.sep24.Sep24TransactionStore;
import org.stellar.anchor.sep31.Sep31DepositInfoGenerator;
import org.stellar.anchor.sep31.Sep31Service;
import org.stellar.anchor.sep31.Sep31TransactionStore;
import org.stellar.anchor.sep38.Sep38QuoteStore;
import org.stellar.anchor.sep38.Sep38Service;
import org.stellar.anchor.sep6.ExchangeAmountsCalculator;
import org.stellar.anchor.sep6.RequestValidator;
import org.stellar.anchor.sep6.Sep6Service;
import org.stellar.anchor.sep6.Sep6TransactionStore;

/** SEP configurations */
@Configuration
public class SepBeans {

  /**********************************
   * SEP configurations
   */
  @Bean
  @ConfigurationProperties(prefix = "sep1")
  Sep1Config sep1Config() {
    return new PropertySep1Config();
  }

  @Bean
  @ConfigurationProperties(prefix = "sep10")
  Sep10Config sep10Config(
      AppConfig appConfig, SecretConfig secretConfig, PropertyClientsConfig clientsConfig) {
    return new PropertySep10Config(appConfig, clientsConfig, secretConfig);
  }

  @Bean
  @ConfigurationProperties(prefix = "sep12")
  Sep12Config sep12Config(CallbackApiConfig callbackApiConfig) {
    return new PropertySep12Config(callbackApiConfig);
  }

  @Bean
  @ConfigurationProperties(prefix = "sep31")
  Sep31Config sep31Config(CustodyConfig custodyConfig, AssetService assetService) {
    return new PropertySep31Config(custodyConfig, assetService);
  }

  @Bean
  @ConfigurationProperties(prefix = "sep38")
  Sep38Config sep38Config() {
    return new PropertySep38Config();
  }

  /**
   * Register sep-10 token filter.
   *
   * @return Spring Filter Registration Bean
   */
  @Bean
  public FilterRegistrationBean<Filter> sep10TokenFilter(
      JwtService jwtService, Sep38Config sep38Config) {
    FilterRegistrationBean<Filter> registrationBean = new FilterRegistrationBean<>();
    registrationBean.setFilter(new Sep10JwtFilter(jwtService));
    registrationBean.addUrlPatterns("/sep6/deposit/*");
    registrationBean.addUrlPatterns("/sep6/deposit-exchange/*");
    registrationBean.addUrlPatterns("/sep6/withdraw/*");
    registrationBean.addUrlPatterns("/sep6/withdraw-exchange/*");
    registrationBean.addUrlPatterns("/sep6/transaction");
    registrationBean.addUrlPatterns("/sep6/transactions*");
    registrationBean.addUrlPatterns("/sep6/transactions/*");
    registrationBean.addUrlPatterns("/sep12/*");
    registrationBean.addUrlPatterns("/sep24/transaction");
    registrationBean.addUrlPatterns("/sep24/transactions*");
    registrationBean.addUrlPatterns("/sep24/transactions/*");
    registrationBean.addUrlPatterns("/sep31/transactions");
    registrationBean.addUrlPatterns("/sep31/transactions/*");
    registrationBean.addUrlPatterns("/sep38/quote");
    registrationBean.addUrlPatterns("/sep38/quote/*");
    if (sep38Config.isSep10Enforced()) {
      registrationBean.addUrlPatterns("/sep38/info");
      registrationBean.addUrlPatterns("/sep38/price");
      registrationBean.addUrlPatterns("/sep38/prices");
    }
    return registrationBean;
  }

  @Bean
  @ConditionalOnAllSepsEnabled(seps = {"sep1"})
  Sep1Service sep1Service(Sep1Config sep1Config) throws IOException, InvalidConfigException {
    return new Sep1Service(sep1Config);
  }

  @Bean
  @ConditionalOnAnySepsEnabled(seps = {"sep6", "sep10", "sep24"})
  ClientFinder clientFinder(Sep10Config sep10Config, ClientsConfig clientsConfig) {
    return new ClientFinder(sep10Config, clientsConfig);
  }

  @Bean
  @ConditionalOnAllSepsEnabled(seps = {"sep6"})
  Sep6Service sep6Service(
      AppConfig appConfig,
      Sep6Config sep6Config,
      AssetService assetService,
      ClientFinder clientFinder,
      Sep6TransactionStore txnStore,
      EventService eventService,
      Sep38QuoteStore sep38QuoteStore,
      MoreInfoUrlConstructor sep6MoreInfoUrlConstructor) {
    RequestValidator requestValidator = new RequestValidator(assetService);
    ExchangeAmountsCalculator exchangeAmountsCalculator =
        new ExchangeAmountsCalculator(sep38QuoteStore);
    return new Sep6Service(
        appConfig,
        sep6Config,
        assetService,
        requestValidator,
        clientFinder,
        txnStore,
        exchangeAmountsCalculator,
        eventService,
        sep6MoreInfoUrlConstructor);
  }

  @Bean
  @ConditionalOnAllSepsEnabled(seps = {"sep10"})
  Sep10Service sep10Service(
      AppConfig appConfig,
      SecretConfig secretConfig,
      Sep10Config sep10Config,
      Horizon horizon,
      JwtService jwtService,
      ClientFinder clientFinder) {
    return new Sep10Service(
        appConfig, secretConfig, sep10Config, horizon, jwtService, clientFinder);
  }

  @Bean
  @ConditionalOnAllSepsEnabled(seps = {"sep12"})
  Sep12Service sep12Service(
      CustomerIntegration customerIntegration,
      AssetService assetService,
      PlatformApiClient platformApiClient,
      EventService eventService) {
    return new Sep12Service(customerIntegration, assetService, platformApiClient, eventService);
  }

  @Bean
  @ConditionalOnAllSepsEnabled(seps = {"sep24"})
  Sep24Service sep24Service(
      AppConfig appConfig,
      Sep24Config sep24Config,
      ClientsConfig clientsConfig,
      AssetService assetService,
      JwtService jwtService,
      ClientFinder clientFinder,
      Sep24TransactionStore sep24TransactionStore,
      EventService eventService,
      InteractiveUrlConstructor interactiveUrlConstructor,
      MoreInfoUrlConstructor sep24MoreInfoUrlConstructor,
      CustodyConfig custodyConfig,
      Sep38QuoteStore sep38QuoteStore) {
    ExchangeAmountsCalculator exchangeAmountsCalculator =
        new ExchangeAmountsCalculator(sep38QuoteStore);
    return new Sep24Service(
        appConfig,
        sep24Config,
        clientsConfig,
        assetService,
        jwtService,
        clientFinder,
        sep24TransactionStore,
        eventService,
        interactiveUrlConstructor,
        sep24MoreInfoUrlConstructor,
        custodyConfig,
        exchangeAmountsCalculator);
  }

  @Bean
  InteractiveUrlConstructor interactiveUrlConstructor(
      PropertyClientsConfig clientsConfig,
      PropertySep24Config sep24Config,
      CustomerIntegration customerIntegration,
      JwtService jwtService) {
    return new SimpleInteractiveUrlConstructor(
        clientsConfig, sep24Config, customerIntegration, jwtService);
  }

  @Bean
  @ConditionalOnAnySepsEnabled(seps = {"sep31"})
  Sep31DepositInfoGenerator sep31DepositInfoGenerator(
      Sep31Config sep31Config,
      PaymentObservingAccountsManager paymentObservingAccountsManager,
      UniqueAddressIntegration uniqueAddressIntegration,
      Optional<CustodyApiClient> custodyApiClient)
      throws InvalidConfigException {
    switch (sep31Config.getDepositInfoGeneratorType()) {
      case SELF:
        return new Sep31DepositInfoSelfGenerator();
      case API:
        return new Sep31DepositInfoApiGenerator(
            uniqueAddressIntegration, paymentObservingAccountsManager);
      case CUSTODY:
        return new Sep31DepositInfoCustodyGenerator(
            custodyApiClient.orElseThrow(
                () ->
                    new InvalidConfigException("Integration with custody service is not enabled")));
      default:
        throw new RuntimeException("Not supported");
    }
  }

  @Bean
  @ConditionalOnAllSepsEnabled(seps = {"sep31"})
  Sep31Service sep31Service(
      AppConfig appConfig,
      Sep10Config sep10Config,
      Sep31Config sep31Config,
      Sep31TransactionStore sep31TransactionStore,
      Sep31DepositInfoGenerator sep31DepositInfoGenerator,
      Sep38QuoteStore sep38QuoteStore,
      PropertyClientsConfig clientsConfig,
      AssetService assetService,
      FeeIntegration feeIntegration,
      CustomerIntegration customerIntegration,
      EventService eventService,
      CustodyService custodyService,
      CustodyConfig custodyConfig) {
    return new Sep31Service(
        appConfig,
        sep10Config,
        sep31Config,
        sep31TransactionStore,
        sep31DepositInfoGenerator,
        sep38QuoteStore,
        clientsConfig,
        assetService,
        feeIntegration,
        customerIntegration,
        eventService,
        custodyService,
        custodyConfig);
  }

  @Bean
  @ConditionalOnAllSepsEnabled(seps = {"sep38"})
  Sep38Service sep38Service(
      Sep38Config sep38Config,
      AssetService assetService,
      RateIntegration rateIntegration,
      Sep38QuoteStore sep38QuoteStore,
      EventService eventService) {
    return new Sep38Service(
        sep38Config, assetService, rateIntegration, sep38QuoteStore, eventService);
  }
}
