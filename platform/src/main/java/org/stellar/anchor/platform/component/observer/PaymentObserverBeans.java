package org.stellar.anchor.platform.component.observer;

import static org.stellar.anchor.util.StringHelper.isNotEmpty;

import java.util.List;
import lombok.SneakyThrows;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.stellar.anchor.api.asset.StellarAssetInfo;
import org.stellar.anchor.api.exception.ServerErrorException;
import org.stellar.anchor.apiclient.PlatformApiClient;
import org.stellar.anchor.asset.AssetService;
import org.stellar.anchor.config.AppConfig;
import org.stellar.anchor.platform.config.PaymentObserverConfig;
import org.stellar.anchor.platform.config.RpcConfig;
import org.stellar.anchor.platform.data.JdbcSep24TransactionStore;
import org.stellar.anchor.platform.data.JdbcSep31TransactionStore;
import org.stellar.anchor.platform.data.JdbcSep6TransactionStore;
import org.stellar.anchor.platform.observer.PaymentListener;
import org.stellar.anchor.platform.observer.stellar.*;

@Configuration
public class PaymentObserverBeans {
  @Bean
  @SneakyThrows
  public AbstractPaymentObserver stellarPaymentObserver(
      AssetService assetService,
      List<PaymentListener> paymentListeners,
      StellarPaymentStreamerCursorStore stellarPaymentStreamerCursorStore,
      PaymentObservingAccountsManager paymentObservingAccountsManager,
      AppConfig appConfig,
      PaymentObserverConfig paymentObserverConfig) {
    // validate assetService
    if (assetService == null || assetService.getAssets() == null) {
      throw new ServerErrorException("Asset service cannot be empty.");
    }
    List<StellarAssetInfo> stellarAssets = assetService.getStellarAssets();
    if (stellarAssets.isEmpty()) {
      throw new ServerErrorException("Asset service should contain at least one Stellar asset.");
    }

    // validate paymentListeners
    if (paymentListeners == null || paymentListeners.isEmpty()) {
      throw new ServerErrorException(
          "The stellar payment observer service needs at least one listener.");
    }

    // validate paymentStreamerCursorStore
    if (stellarPaymentStreamerCursorStore == null) {
      throw new ServerErrorException("Payment streamer cursor store cannot be empty.");
    }

    // validate appConfig
    if (appConfig == null) {
      throw new ServerErrorException("AppConfig cannot be empty.");
    }

    if (paymentObserverConfig == null) {
      throw new ServerErrorException("PaymentObserverConfig cannot be empty.");
    }

    // Add distribution wallet to the observing list as type RESIDENTIAL
    for (StellarAssetInfo asset : stellarAssets) {
      if (!paymentObservingAccountsManager.lookupAndUpdate(asset.getDistributionAccount())) {
        paymentObservingAccountsManager.upsert(
            asset.getDistributionAccount(),
            PaymentObservingAccountsManager.AccountType.RESIDENTIAL);
      }
    }

    AbstractPaymentObserver paymentObserver;
    if (isNotEmpty(appConfig.getRpcUrl())) {
      paymentObserver =
          new SorobanPaymentObserver(
              appConfig.getRpcUrl(),
              paymentObserverConfig.getStellar(),
              paymentListeners,
              paymentObservingAccountsManager,
              stellarPaymentStreamerCursorStore);
    } else if (isNotEmpty(appConfig.getHorizonUrl())) {
      paymentObserver =
          new HorizonPaymentObserver(
              appConfig.getHorizonUrl(),
              paymentObserverConfig.getStellar(),
              paymentListeners,
              paymentObservingAccountsManager,
              stellarPaymentStreamerCursorStore);
    } else {
      throw new IllegalArgumentException("Either RPC or Horizon URL must be provided.");
    }

    paymentObserver.start();
    return paymentObserver;
  }

  @Bean
  public DefaultPaymentListener paymentListener(
      PaymentObservingAccountsManager paymentObservingAccountsManager,
      JdbcSep31TransactionStore sep31TransactionStore,
      JdbcSep24TransactionStore sep24TransactionStore,
      JdbcSep6TransactionStore sep6TransactionStore,
      PlatformApiClient platformApiClient,
      RpcConfig rpcConfig) {

    return new DefaultPaymentListener(
        paymentObservingAccountsManager,
        sep31TransactionStore,
        sep24TransactionStore,
        sep6TransactionStore,
        platformApiClient,
        rpcConfig);
  }
}
