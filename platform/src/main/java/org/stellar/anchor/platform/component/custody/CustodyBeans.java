package org.stellar.anchor.platform.component.custody;

import java.util.concurrent.TimeUnit;
import javax.servlet.Filter;
import okhttp3.OkHttpClient;
import okhttp3.OkHttpClient.Builder;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.stellar.anchor.api.custody.fireblocks.TransactionDetails;
import org.stellar.anchor.apiclient.PlatformApiClient;
import org.stellar.anchor.auth.JwtService;
import org.stellar.anchor.filter.ApiKeyFilter;
import org.stellar.anchor.filter.CustodyAuthJwtFilter;
import org.stellar.anchor.filter.NoneFilter;
import org.stellar.anchor.metrics.MetricsService;
import org.stellar.anchor.platform.config.CustodyApiConfig;
import org.stellar.anchor.platform.config.PropertyCustodyConfig;
import org.stellar.anchor.platform.config.RpcConfig;
import org.stellar.anchor.platform.custody.*;
import org.stellar.anchor.platform.data.JdbcCustodyTransactionRepo;

@Configuration
public class CustodyBeans {

  /**
   * Register platform-to-custody token filter.
   *
   * @return Spring Filter Registration Bean
   */
  @Bean
  public FilterRegistrationBean<Filter> platformToCustodyTokenFilter(
      CustodyApiConfig custodyApiConfig) {
    String authSecret = custodyApiConfig.getAuth().getSecretString();

    Filter platformToCustody;
    switch (custodyApiConfig.getAuth().getType()) {
      case JWT:
        JwtService jwtService = JwtService.builder().custodyAuthSecret(authSecret).build();
        platformToCustody =
            new CustodyAuthJwtFilter(
                jwtService, custodyApiConfig.getAuth().getJwt().getHttpHeader());
        break;

      case API_KEY:
        platformToCustody =
            new ApiKeyFilter(authSecret, custodyApiConfig.getAuth().getApiKey().getHttpHeader());
        break;

      default:
        platformToCustody = new NoneFilter();
        break;
    }

    FilterRegistrationBean<Filter> registrationBean = new FilterRegistrationBean<>();
    registrationBean.setFilter(platformToCustody);
    registrationBean.addUrlPatterns("/transactions/*");
    return registrationBean;
  }

  @Bean
  Sep6CustodyPaymentHandler sep6CustodyPaymentHandler(
      JdbcCustodyTransactionRepo custodyTransactionRepo,
      PlatformApiClient platformApiClient,
      RpcConfig rpcConfig,
      MetricsService metricsService) {
    return new Sep6CustodyPaymentHandler(
        custodyTransactionRepo, platformApiClient, rpcConfig, metricsService);
  }

  @Bean
  Sep24CustodyPaymentHandler sep24CustodyPaymentHandler(
      JdbcCustodyTransactionRepo custodyTransactionRepo,
      PlatformApiClient platformApiClient,
      RpcConfig rpcConfig,
      MetricsService metricsService) {
    return new Sep24CustodyPaymentHandler(
        custodyTransactionRepo, platformApiClient, rpcConfig, metricsService);
  }

  @Bean
  Sep31CustodyPaymentHandler sep31CustodyPaymentHandler(
      JdbcCustodyTransactionRepo custodyTransactionRepo,
      PlatformApiClient platformApiClient,
      RpcConfig rpcConfig,
      MetricsService metricsService) {
    return new Sep31CustodyPaymentHandler(
        custodyTransactionRepo, platformApiClient, rpcConfig, metricsService);
  }

  @Bean(name = "custodyHttpClient")
  OkHttpClient custodyHttpClient(PropertyCustodyConfig custodyConfig) {
    return new Builder()
        .connectTimeout(custodyConfig.getHttpClient().getConnectTimeout(), TimeUnit.SECONDS)
        .readTimeout(custodyConfig.getHttpClient().getReadTimeout(), TimeUnit.SECONDS)
        .writeTimeout(custodyConfig.getHttpClient().getWriteTimeout(), TimeUnit.SECONDS)
        .callTimeout(custodyConfig.getHttpClient().getCallTimeout(), TimeUnit.SECONDS)
        .build();
  }

  @Bean
  CustodyTransactionService custodyTransactionService(
      JdbcCustodyTransactionRepo custodyTransactionRepo,
      CustodyPaymentService<TransactionDetails> custodyPaymentService) {
    return new CustodyTransactionService(custodyTransactionRepo, custodyPaymentService);
  }
}
