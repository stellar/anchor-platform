package org.stellar.anchor.platform;

import com.google.gson.Gson;
import java.util.concurrent.TimeUnit;
import okhttp3.OkHttpClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.stellar.anchor.api.callback.CustomerIntegration;
import org.stellar.anchor.api.callback.FeeIntegration;
import org.stellar.anchor.api.callback.RateIntegration;
import org.stellar.anchor.config.Sep12Config;
import org.stellar.anchor.config.Sep31Config;
import org.stellar.anchor.config.Sep38Config;
import org.stellar.anchor.platform.callback.RestCustomerIntegration;
import org.stellar.anchor.platform.callback.RestFeeIntegration;
import org.stellar.anchor.platform.callback.RestRateIntegration;

@Configuration
public class IntegrationConfig {
  @Bean
  OkHttpClient httpClient() {
    return new OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.MINUTES)
        .readTimeout(10, TimeUnit.MINUTES)
        .writeTimeout(10, TimeUnit.MINUTES)
        .callTimeout(10, TimeUnit.MINUTES)
        .build();
  }

  @Bean
  CustomerIntegration customerIntegration(
      Sep12Config sep12Config, OkHttpClient httpClient, Gson gson) {
    return new RestCustomerIntegration(
        sep12Config.getCustomerIntegrationEndPoint(), httpClient, gson);
  }

  @Bean
  RateIntegration rateIntegration(Sep38Config sep38Config, OkHttpClient httpClient, Gson gson) {
    return new RestRateIntegration(sep38Config.getQuoteIntegrationEndPoint(), httpClient, gson);
  }

  @Bean
  FeeIntegration feeIntegration(Sep31Config sep31Config, Gson gson) {
    return new RestFeeIntegration(sep31Config.getFeeIntegrationEndPoint(), httpClient(), gson);
  }
}
