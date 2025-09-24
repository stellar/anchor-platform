package org.stellar.anchor.platform.component.sep;

import com.google.gson.Gson;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import okhttp3.OkHttpClient;
import okhttp3.OkHttpClient.Builder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.stellar.anchor.api.callback.CustomerIntegration;
import org.stellar.anchor.api.callback.FeeIntegration;
import org.stellar.anchor.api.callback.RateIntegration;
import org.stellar.anchor.api.callback.UniqueAddressIntegration;
import org.stellar.anchor.asset.AssetService;
import org.stellar.anchor.auth.AuthHelper;
import org.stellar.anchor.platform.callback.RestCustomerIntegration;
import org.stellar.anchor.platform.callback.RestFeeIntegration;
import org.stellar.anchor.platform.callback.RestRateIntegration;
import org.stellar.anchor.platform.callback.RestUniqueAddressIntegration;
import org.stellar.anchor.platform.config.CallbackApiConfig;

@Configuration
public class ApiClientBeans {
  TrustManager[] trustAllCerts =
      new TrustManager[] {
        new X509TrustManager() {
          @Override
          public void checkClientTrusted(
              java.security.cert.X509Certificate[] chain, String authType) {}

          @Override
          public void checkServerTrusted(
              java.security.cert.X509Certificate[] chain, String authType) {}

          @Override
          public java.security.cert.X509Certificate[] getAcceptedIssuers() {
            return new java.security.cert.X509Certificate[] {};
          }
        }
      };

  @Bean
  OkHttpClient httpClient(CallbackApiConfig callbackApiConfig)
      throws NoSuchAlgorithmException, KeyManagementException {
    Builder builder =
        new Builder()
            .connectTimeout(10, TimeUnit.MINUTES)
            .readTimeout(10, TimeUnit.MINUTES)
            .writeTimeout(10, TimeUnit.MINUTES)
            .callTimeout(10, TimeUnit.MINUTES);

    if (!callbackApiConfig.getCheckCertificate()) {
      SSLContext sslContext = SSLContext.getInstance("SSL");
      sslContext.init(null, trustAllCerts, new java.security.SecureRandom());
      builder
          .sslSocketFactory(sslContext.getSocketFactory(), (X509TrustManager) trustAllCerts[0])
          .hostnameVerifier((hostname, session) -> true);
    }
    return builder.build();
  }

  @Bean
  UniqueAddressIntegration uniqueAddressIntegration(
      CallbackApiConfig callbackApiConfig, OkHttpClient httpClient, Gson gson) {
    AuthHelper authHelper = callbackApiConfig.buildAuthHelper();
    return new RestUniqueAddressIntegration(
        callbackApiConfig.getBaseUrl(), httpClient, authHelper, gson);
  }

  @Bean
  CustomerIntegration customerIntegration(
      CallbackApiConfig callbackApiConfig, OkHttpClient httpClient, Gson gson) {
    return new RestCustomerIntegration(
        callbackApiConfig.getBaseUrl(), httpClient, callbackApiConfig.buildAuthHelper(), gson);
  }

  @Bean
  RateIntegration rateIntegration(
      CallbackApiConfig callbackApiConfig,
      OkHttpClient httpClient,
      Gson gson,
      AssetService assetService) {
    return new RestRateIntegration(
        callbackApiConfig.getBaseUrl(),
        httpClient,
        callbackApiConfig.buildAuthHelper(),
        gson,
        assetService);
  }

  @Bean
  FeeIntegration feeIntegration(
      CallbackApiConfig callbackApiConfig, OkHttpClient httpClient, Gson gson) {
    return new RestFeeIntegration(
        callbackApiConfig.getBaseUrl(), httpClient, callbackApiConfig.buildAuthHelper(), gson);
  }
}
