package org.stellar.anchor.platform.component.observer;

import java.util.List;
import lombok.SneakyThrows;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.stellar.anchor.api.asset.StellarAssetInfo;
import org.stellar.anchor.api.exception.ServerErrorException;
import org.stellar.anchor.apiclient.PlatformApiClient;
import org.stellar.anchor.asset.AssetService;
import org.stellar.anchor.config.StellarNetworkConfig;
import org.stellar.anchor.ledger.Horizon;
import org.stellar.anchor.ledger.LedgerClient;
import org.stellar.anchor.ledger.StellarRpc;
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
      LedgerClient ledgerClient,
      AssetService assetService,
      List<PaymentListener> paymentListeners,
      StellarPaymentStreamerCursorStore stellarPaymentStreamerCursorStore,
      PaymentObservingAccountsManager paymentObservingAccountsManager,
      StellarNetworkConfig stellarNetworkConfig,
      PaymentObserverConfig paymentObserverConfig,
      SacToAssetMapper sacToAssetMapper) {
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
    if (stellarNetworkConfig == null) {
      throw new ServerErrorException("AppConfig cannot be empty.");
    }

    if (paymentObserverConfig == null) {
      throw new ServerErrorException("PaymentObserverConfig cannot be empty.");
    }
    seedDistributionAccounts(paymentObservingAccountsManager, stellarAssets);
    AbstractPaymentObserver paymentObserver;
    if (ledgerClient instanceof StellarRpc stellarRpc) {
      paymentObserver =
          new StellarRpcPaymentObserver(
              stellarRpc,
              paymentObserverConfig.getStellar(),
              paymentListeners,
              paymentObservingAccountsManager,
              stellarPaymentStreamerCursorStore,
              sacToAssetMapper,
              assetService);
    } else if (ledgerClient instanceof Horizon horizon) {
      paymentObserver =
          new HorizonPaymentObserver(
              horizon,
              paymentObserverConfig.getStellar(),
              paymentListeners,
              paymentObservingAccountsManager,
              stellarPaymentStreamerCursorStore);
    } else {
      throw new IllegalArgumentException(
          "The ledger client type is not supported: " + ledgerClient.getClass().getName());
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
      RpcConfig rpcConfig,
      AssetService assetService) {

    return new DefaultPaymentListener(
        paymentObservingAccountsManager,
        sep31TransactionStore,
        sep24TransactionStore,
        sep6TransactionStore,
        platformApiClient,
        rpcConfig,
        assetService);
  }

  /**
   * Add the distribution wallets to the observing list as type RESIDENTIAL. The account type is not
   * persisted, so on a restart the manager loads the stored distribution accounts as TRANSIENT;
   * upserting unconditionally promotes them back to RESIDENTIAL so they are never evicted.
   */
  public static void seedDistributionAccounts(
      PaymentObservingAccountsManager paymentObservingAccountsManager,
      List<StellarAssetInfo> stellarAssets) {
    for (StellarAssetInfo asset : stellarAssets) {
      paymentObservingAccountsManager.upsert(
          asset.getDistributionAccount(), PaymentObservingAccountsManager.AccountType.RESIDENTIAL);
    }
  }
}
